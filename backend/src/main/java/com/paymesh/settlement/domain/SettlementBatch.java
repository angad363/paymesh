package com.paymesh.settlement.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.List;

/**
 * One merchant's available balance in one currency, at one instant, itemised. SDD 17.1, ADR-032.
 *
 * <h2>CUT COMPLETE, AND THE ITEMS ARE THE AMOUNT</h2>
 *
 * A batch is not accumulated. It is taken whole from what the ledger says is available, so the net
 * is the sum of the items by construction -- and {@code tr_settlement_batches_total} is what makes
 * that true rather than intended, a deferred constraint trigger in the same shape as the ledger's
 * debits-equal-credits check.
 *
 * <h2>An item can be negative</h2>
 *
 * A payment refunded after its funds were released owes money back, and the merchant's available
 * balance already reflects that. Dropping such an item would leave the batch claiming more than the
 * balance it was cut from -- and the trigger would catch it, which is the point of having the
 * trigger. SDD 17.1 calls this an adjustment; here it is a row, traceable to the payment that
 * caused it rather than rolled into a column.
 */
public record SettlementBatch(
    SettlementBatchId settlementBatchId,
    MerchantId merchantId,
    String currency,
    long netAmountMinor,
    SettlementBatchStatus status,
    List<SettlementItem> items,
    Instant cutAt,
    Instant createdAt,
    Instant updatedAt
) {

    public SettlementBatch {
        if (settlementBatchId == null || merchantId == null) {
            throw new IllegalArgumentException("A settlement batch needs an id and a merchant");
        }

        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("A settlement batch needs an ISO 4217 currency");
        }

        if (status == null) {
            throw new IllegalArgumentException("A settlement batch needs a status");
        }

        items = List.copyOf(items == null ? List.of() : items);

        if (items.isEmpty()) {
            throw new IllegalArgumentException("A settlement batch with no items settles nothing");
        }

        // THE SAME ARITHMETIC THE TRIGGER ENFORCES, CHECKED HERE FOR THE ERROR MESSAGE.
        // ck_settlement_batches_net and tr_settlement_batches_total are the guards; this is what
        // turns a violation into something readable before it ever reaches the database.
        long itemTotal = items.stream().mapToLong(SettlementItem::amountMinor).sum();

        if (itemTotal != netAmountMinor) {
            throw new IllegalArgumentException(
                "Settlement batch net " + netAmountMinor + " does not match its items " + itemTotal
            );
        }

        if (netAmountMinor <= 0) {
            throw new IllegalArgumentException(
                "A settlement batch settles a positive amount, got " + netAmountMinor
            );
        }

        if (cutAt == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("A settlement batch must be timestamped");
        }
    }

    /**
     * Cut a batch for everything the merchant has available.
     *
     * @param contributions one entry per payment with a non-zero contribution, INCLUDING negative
     *     ones. The caller has already netted off whatever earlier batches took.
     */
    public static SettlementBatch cut(
        MerchantId merchantId,
        String currency,
        List<SettlementItem> contributions,
        Instant now
    ) {
        long net = contributions.stream().mapToLong(SettlementItem::amountMinor).sum();

        return new SettlementBatch(
            SettlementBatchId.generate(),
            merchantId,
            currency,
            net,
            SettlementBatchStatus.PENDING_PAYOUT,
            contributions,
            now,
            now,
            now
        );
    }

    /** The provider confirmed. Intent-revealing, like every other transition in this codebase. */
    public SettlementBatch markPaid(Instant now) {
        return transitionTo(SettlementBatchStatus.PAID, now);
    }

    /** The payout failed terminally; the funds are going back to available. */
    public SettlementBatch markReturned(Instant now) {
        return transitionTo(SettlementBatchStatus.RETURNED, now);
    }

    private SettlementBatch transitionTo(SettlementBatchStatus target, Instant now) {
        if (status != SettlementBatchStatus.PENDING_PAYOUT) {
            throw new SettlementBatchNotPendingException(settlementBatchId, status);
        }

        return new SettlementBatch(
            settlementBatchId, merchantId, currency, netAmountMinor, target, items, cutAt,
            createdAt, now
        );
    }
}
