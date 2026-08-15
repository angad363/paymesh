package com.paymesh.settlement.domain;

import java.util.UUID;

/**
 * One payment's contribution to a batch. SDD 17.1's {@code settlement_items}.
 *
 * <h2>A PAYMENT, NOT A RELEASE JOURNAL</h2>
 *
 * The obvious itemisation is one row per {@code funds-released} journal and it is wrong for the
 * same reason releasing the gross was wrong (ADR-031): a refund landing after release debits the
 * available account without producing a release journal to point at, so the items would sum to more
 * than the balance they describe. A payment identifies all of its own movements; a journal
 * identifies one.
 *
 * <p>The id is a plain prefixed string rather than a value-object record, unlike
 * {@link SettlementBatchId} and {@link PayoutId}: an item is never addressed on its own -- it has
 * no endpoint, no callback and no foreign key pointing at it from outside the batch -- so a type
 * whose job is to validate what a caller passed in has no caller to validate.
 * {@code ck_settlement_items_id_format} still refuses a malformed one at the database (ADR-029).
 *
 * @param amountMinor signed. Negative is a payment refunded past its own release, and it belongs in
 *     the batch: dropping it pays out money the merchant no longer has
 */
public record SettlementItem(String settlementItemId, String paymentIntentId, long amountMinor) {

    private static final String PREFIX = "sti_";

    public SettlementItem {
        if (settlementItemId == null || !settlementItemId.startsWith(PREFIX)) {
            throw new IllegalArgumentException("A settlement item identifier starts with " + PREFIX);
        }

        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("A settlement item names the payment it came from");
        }

        if (amountMinor == 0) {
            throw new IllegalArgumentException(
                "A zero settlement item says nothing happened and occupies the payment's only slot"
            );
        }
    }

    public static SettlementItem of(String paymentIntentId, long amountMinor) {
        return new SettlementItem(PREFIX + UUID.randomUUID(), paymentIntentId, amountMinor);
    }
}
