package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.settlement.domain.SettlementConfig;
import com.paymesh.settlement.domain.SettlementItem;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a merchant's available balance into a batch and a payout. SDD 17.1, ADR-032.
 *
 * <h2>THE NET IS THE LEDGER'S ANSWER MINUS WHAT IS ALREADY BATCHED</h2>
 *
 * The Ledger reports what each payment has contributed to the available account. Settlement
 * subtracts what earlier, non-returned batches already took from each of those payments. What is
 * left is exactly the available balance -- the two agree because {@code tr_settlement_batches_total}
 * makes a batch's ledger debit equal the sum of its items -- and it arrives itemised, which is what
 * a merchant reconciling a statement against their own orders needs.
 * <p>
 * <b>Why not just read the balance?</b> Because a total cannot be reconciled. The alternative is
 * one query for the figure and a second for the breakdown, which is two chances for the two to
 * disagree about the same money.
 *
 * <h2>Nothing here posts to the ledger</h2>
 *
 * The batch, its items, the payout and a {@code settlement.batch_cut} event commit in one
 * transaction. The Ledger consumes that event and moves available to in-transit. So the funds are
 * committed a moment AFTER the batch row exists rather than at the same instant, which is the
 * ordinary at-least-once delivery this platform runs on -- and the window is safe because a second
 * pass finds the batch already recorded and nets it off.
 */
public final class CutSettlementBatchesService {

    private static final Logger log = LoggerFactory.getLogger(CutSettlementBatchesService.class);

    private static final int BATCH_CUT_EVENT_VERSION = 1;

    private final SettlementBatchRepository batches;
    private final PayoutRepository payouts;
    private final GetSettlementConfigService configs;
    private final AvailableFunds availableFunds;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CutSettlementBatchesService(
        SettlementBatchRepository batches,
        PayoutRepository payouts,
        GetSettlementConfigService configs,
        AvailableFunds availableFunds,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.batches = batches;
        this.payouts = payouts;
        this.configs = configs;
        this.availableFunds = availableFunds;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Cut whatever this merchant can be paid, one batch per currency.
     *
     * @return the batches cut, which is empty far more often than not -- a merchant with nothing
     *     settleable is the normal case and not a condition to log about
     */
    public List<SettlementBatch> cutFor(MerchantId merchantId) {
        SettlementConfig config = configs.forMerchant(merchantId);

        if (!config.isPayable()) {
            // NO DESTINATION, NO BATCH. Cutting one would move the money into a transit account
            // with nowhere to go, and it would sit there un-settleable until somebody noticed.
            return List.of();
        }

        Map<String, List<SettlementItem>> settleable = settleableByCurrency(merchantId);
        List<SettlementBatch> cut = new ArrayList<>();

        for (Map.Entry<String, List<SettlementItem>> entry : settleable.entrySet()) {
            String currency = entry.getKey();
            long net = entry.getValue().stream().mapToLong(SettlementItem::amountMinor).sum();

            // A net of zero or less is not a batch. Negative means the merchant owes PayMesh -- the
            // refunds outweigh what was released -- and that is carried in the available balance
            // until fresh payments cover it, which is the only place it can be carried honestly.
            if (net <= 0 || !config.meetsMinimum(net)) {
                continue;
            }

            cut.add(cutOne(merchantId, currency, entry.getValue(), config.payoutDestination()));
        }

        return cut;
    }

    /**
     * What each payment still has in available, netted against every batch that has taken from it.
     * <p>
     * Returns only non-zero contributions, because a zero is a payment fully accounted for and
     * {@code ck_settlement_items_amount} refuses it as an item anyway.
     */
    private Map<String, List<SettlementItem>> settleableByCurrency(MerchantId merchantId) {
        // Keyed on currency AND payment, because the same payment cannot appear in two currencies
        // but two payments can appear in one, and the batch is per currency.
        Map<String, Map<String, Long>> byCurrency = new LinkedHashMap<>();

        for (AvailableFunds.PaymentContribution contribution
            : availableFunds.contributions(merchantId)) {

            byCurrency
                .computeIfAbsent(contribution.currency(), unused -> new LinkedHashMap<>())
                .merge(contribution.paymentIntentId(), contribution.amountMinor(), Long::sum);
        }

        for (SettlementBatchRepository.BatchedAmount batched : batches.batchedAmounts(merchantId)) {
            Map<String, Long> payments = byCurrency.get(batched.currency());

            if (payments != null) {
                payments.merge(batched.paymentIntentId(), -batched.amountMinor(), Long::sum);
            }
        }

        Map<String, List<SettlementItem>> settleable = new LinkedHashMap<>();

        byCurrency.forEach((currency, payments) -> {
            List<SettlementItem> items = payments.entrySet().stream()
                .filter(payment -> payment.getValue() != 0)
                .map(payment -> SettlementItem.of(payment.getKey(), payment.getValue()))
                .toList();

            if (!items.isEmpty()) {
                settleable.put(currency, items);
            }
        });

        return settleable;
    }

    /** The batch, its items, its payout and its event, in one transaction (ADR-010). */
    private SettlementBatch cutOne(
        MerchantId merchantId, String currency, List<SettlementItem> items, String destination
    ) {
        return transactions.execute(status -> {
            Instant now = Instant.now(clock);

            SettlementBatch batch = batches.save(
                SettlementBatch.cut(merchantId, currency, items, now)
            );

            payouts.save(Payout.create(batch, destination, now));
            outbox.append(batchCut(batch));

            log.info(
                "Cut settlement batch settlementBatchId={} merchantId={} net={} {}",
                batch.settlementBatchId().value(), merchantId.value(), batch.netAmountMinor(),
                currency
            );

            return batch;
        });
    }

    private static OutboxEvent batchCut(SettlementBatch batch) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("settlementBatchId", batch.settlementBatchId().value());
        payload.put("merchantId", batch.merchantId().value());
        payload.put("amountMinor", batch.netAmountMinor());
        payload.put("currency", batch.currency());
        payload.put("itemCount", batch.items().size());
        payload.put("occurredAt", batch.cutAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            batch.merchantId(),
            "SETTLEMENT_BATCH",
            batch.settlementBatchId().value(),
            "settlement.batch_cut",
            BATCH_CUT_EVENT_VERSION,
            payload,
            batch.cutAt()
        );
    }
}
