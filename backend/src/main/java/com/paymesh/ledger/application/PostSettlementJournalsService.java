package com.paymesh.ledger.application;

import com.paymesh.ledger.domain.LedgerAccount;
import com.paymesh.ledger.domain.LedgerTransaction;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * The three journals a settlement produces. SDD 17.6 invariants 2 and 3, ADR-032.
 *
 * <h2>THE LEDGER POSTS THESE, NOT SETTLEMENT, AND THE DIFFERENCE IS THE WHOLE OF ADR-018 §3</h2>
 *
 * Settlement writes rows and an outbox event; the Ledger consumes the event and posts. The shorter
 * route -- Settlement calling a ledger service directly, since it already reads balances through a
 * port -- is exactly the internal posting API ADR-018 refused: a second way into the financial
 * source of truth with no committed state change to reconcile a posting against. Going through the
 * outbox means every journal here traces to a row that committed, and a redelivery is a no-op
 * because the idempotency key is the batch.
 *
 * <h2>Three postings, one shape</h2>
 *
 * <pre>
 *   batch cut   DEBIT  available     CREDIT in-transit   -- committed, not settleable again
 *   payout paid DEBIT  in-transit    CREDIT bank-cash    -- discharged, and the cash is gone
 *   returned    DEBIT  in-transit    CREDIT available    -- settleable again, by a NEW journal
 * </pre>
 *
 * The third is SDD 17.6's third invariant and it is a reversal rather than an edit for the reason
 * the whole ledger is append-only: "cut then returned" and "never cut" are different histories, and
 * only one of them happened.
 */
public final class PostSettlementJournalsService {

    private static final Logger log = LoggerFactory.getLogger(PostSettlementJournalsService.class);

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final Clock clock;

    public PostSettlementJournalsService(
        LedgerAccountRepository accounts,
        LedgerTransactionRepository transactions,
        Clock clock
    ) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Available becomes in-transit. */
    public Optional<LedgerTransaction> postBatchCut(
        MerchantId merchantId,
        String settlementBatchId,
        long amountMinor,
        String currency,
        Instant occurredAt
    ) {
        return post(
            LedgerTransaction.settlementBatchCutIdempotencyKey(settlementBatchId),
            merchantId,
            amountMinor,
            now -> LedgerTransaction.settlementBatchCut(
                merchantId,
                settlementBatchId,
                accounts.open(LedgerAccount.merchantAvailable(merchantId, currency, now)).ledgerAccountId(),
                accounts.open(LedgerAccount.settlementInTransit(merchantId, currency, now)).ledgerAccountId(),
                amountMinor,
                currency,
                occurredAt,
                now
            )
        );
    }

    /** In-transit is discharged against PayMesh's own cash. */
    public Optional<LedgerTransaction> postPayoutPaid(
        MerchantId merchantId,
        String settlementBatchId,
        long amountMinor,
        String currency,
        Instant occurredAt
    ) {
        return post(
            LedgerTransaction.payoutPaidIdempotencyKey(settlementBatchId),
            merchantId,
            amountMinor,
            now -> LedgerTransaction.payoutPaid(
                merchantId,
                settlementBatchId,
                accounts.open(LedgerAccount.settlementInTransit(merchantId, currency, now)).ledgerAccountId(),
                accounts.open(LedgerAccount.bankCash(currency, now)).ledgerAccountId(),
                amountMinor,
                currency,
                occurredAt,
                now
            )
        );
    }

    /** In-transit goes back to available, as a new journal. */
    public Optional<LedgerTransaction> postPayoutReturned(
        MerchantId merchantId,
        String settlementBatchId,
        long amountMinor,
        String currency,
        Instant occurredAt
    ) {
        return post(
            LedgerTransaction.payoutReturnedIdempotencyKey(settlementBatchId),
            merchantId,
            amountMinor,
            now -> LedgerTransaction.payoutReturned(
                merchantId,
                settlementBatchId,
                accounts.open(LedgerAccount.settlementInTransit(merchantId, currency, now)).ledgerAccountId(),
                accounts.open(LedgerAccount.merchantAvailable(merchantId, currency, now)).ledgerAccountId(),
                amountMinor,
                currency,
                occurredAt,
                now
            )
        );
    }

    /**
     * The part all three share: refuse a zero, return the existing journal on a redelivery, and
     * otherwise post.
     * <p>
     * The pre-read is a friendly answer to the ordinary case and NOT the guard --
     * {@code uq_ledger_transactions_idempotency} is, and two concurrent deliveries both find
     * nothing here and the index picks the winner. The loser's transaction is aborted, which is
     * why nothing after {@code post} tries to read.
     */
    private Optional<LedgerTransaction> post(
        String idempotencyKey,
        MerchantId merchantId,
        long amountMinor,
        Function<Instant, LedgerTransaction> journal
    ) {
        if (amountMinor <= 0) {
            // A batch is never cut for a non-positive net (ck_settlement_batches_net), so this
            // means an event carrying an amount its own table would have refused.
            throw new IllegalArgumentException(
                "A settlement journal needs a positive amount, got " + amountMinor
            );
        }

        Optional<LedgerTransaction> alreadyPosted = transactions.findByIdempotencyKey(idempotencyKey);

        if (alreadyPosted.isPresent()) {
            log.info(
                "Settlement journal is already posted, nothing to do idempotencyKey={} merchantId={}",
                idempotencyKey, merchantId.value()
            );

            return alreadyPosted;
        }

        return Optional.of(transactions.post(journal.apply(Instant.now(clock))));
    }
}
