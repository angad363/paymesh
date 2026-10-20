package com.paymesh.refund.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * The refund aggregate.
 *
 * <h2>INTENT METHODS, NEVER A SETTER</h2>
 *
 * A caller asks for {@code submit()}, {@code succeed()}, {@code fail()} or {@code cancel()}; none
 * of them takes a status. Each refuses the transitions that are not legal from where the refund
 * currently is, so an illegal move is not a bug to be caught in review -- it is a method that
 * throws. This is the same shape {@code PaymentIntent} and {@code Order} use.
 *
 * <h2>The amount is fixed at creation and never moves</h2>
 *
 * Every transition below copies {@code amountMinor} through unchanged. A refund whose amount could
 * be edited after the over-refund check had passed would make that check meaningless, and the
 * database agrees -- {@code tr_refunds_within_captured} fires on UPDATE as well as INSERT.
 */
public record Refund(
    RefundId refundId,
    MerchantId merchantId,
    String paymentIntentId,
    long amountMinor,
    String currency,
    RefundStatus status,
    String merchantReference,
    String reason,
    String providerReference,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt
) {

    public Refund {
        if (refundId == null) {
            throw new IllegalArgumentException("Refund identifier is required");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("A refund must belong to a merchant");
        }

        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("A refund must name a payment intent");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                "A refund amount must be a positive number of minor units, got " + amountMinor
            );
        }

        if (status == null) {
            throw new IllegalArgumentException("Refund status is required");
        }

        currency = requireCurrency(currency);
        merchantReference = blankToNull(merchantReference);
        reason = blankToNull(reason);

        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Refund timestamps are required");
        }
    }

    /**
     * A refund the merchant has asked for and nothing has acted on yet.
     * <p>
     * PENDING rather than PROCESSING, and the gap is the whole reason {@code cancel()} exists: a
     * refund that went straight to the provider could never be withdrawn, and SDD 16.3 asks for a
     * cancel endpoint. It also means the row -- and therefore the over-refund check -- exists
     * before anybody is told to move money, which is the correct order for those two things.
     */
    public static Refund request(
        RefundId refundId,
        MerchantId merchantId,
        String paymentIntentId,
        long amountMinor,
        String currency,
        String merchantReference,
        String reason,
        Instant now
    ) {
        return new Refund(
            refundId, merchantId, paymentIntentId, amountMinor, currency,
            RefundStatus.PENDING, merchantReference, reason, null, null, null, now, now
        );
    }

    /** Handed to the provider. */
    public Refund submit(Instant now) {
        requireStatus(RefundStatus.PENDING, "submitted to the provider");

        return withStatus(RefundStatus.PROCESSING, now);
    }

    /**
     * The provider returned the money.
     * <p>
     * Allowed from PROCESSING only. Not from PENDING -- a success for a refund nobody has been
     * asked to perform is a callback for something that did not happen, and accepting it would let
     * a forged or misrouted callback complete a refund PayMesh never submitted.
     */
    public Refund succeed(String providerReference, Instant now) {
        requireStatus(RefundStatus.PROCESSING, "marked succeeded");

        return new Refund(
            refundId, merchantId, paymentIntentId, amountMinor, currency,
            RefundStatus.SUCCEEDED, merchantReference, reason,
            blankToNull(providerReference), null, null, createdAt, now
        );
    }

    /** The provider refused. The amount stops counting against the captured total. */
    public Refund fail(String failureCode, String failureMessage, Instant now) {
        requireStatus(RefundStatus.PROCESSING, "marked failed");

        return new Refund(
            refundId, merchantId, paymentIntentId, amountMinor, currency,
            RefundStatus.FAILED, merchantReference, reason, providerReference,
            blankToNull(failureCode), blankToNull(failureMessage), createdAt, now
        );
    }

    /**
     * Withdrawn before the provider saw it.
     * <p>
     * PENDING only. Once it is PROCESSING the provider may already have moved the money, and
     * "cancelled" would then be PayMesh's opinion rather than a fact -- the merchant would read a
     * cancelled refund while their customer had the money back.
     */
    public Refund cancel(Instant now) {
        requireStatus(RefundStatus.PENDING, "cancelled");

        return withStatus(RefundStatus.CANCELLED, now);
    }

    /** True when this refund's amount is spoken for. See {@link RefundStatus}. */
    public boolean countsAgainstCapturedAmount() {
        return status.countsAgainstCapturedAmount();
    }

    private Refund withStatus(RefundStatus next, Instant now) {
        return new Refund(
            refundId, merchantId, paymentIntentId, amountMinor, currency, next,
            merchantReference, reason, providerReference, failureCode, failureMessage,
            createdAt, now
        );
    }

    private void requireStatus(RefundStatus required, String action) {
        if (status != required) {
            throw new RefundNotInStateException(refundId, status, required, action);
        }
    }

    private static String requireCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }

        String normalised = currency.strip().toUpperCase();

        if (!normalised.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be three letters, got " + currency);
        }

        return normalised;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
