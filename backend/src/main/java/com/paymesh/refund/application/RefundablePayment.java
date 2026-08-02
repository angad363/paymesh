package com.paymesh.refund.application;

/**
 * The four facts Refund needs about a payment. Refund's own type, filled by the adapter.
 *
 * @param paymentIntentId the "pi_" identifier, carried by value across the boundary
 * @param capturedAmountMinor WHAT WAS ACTUALLY COLLECTED, never the authorized amount. On a partial
 *     capture the two differ by the money that was never taken, and refunding against the
 *     authorization would send out funds that never came in.
 * @param currency the payment's currency. A refund must be denominated in it -- comparing bare
 *     integers across currencies is how 5000 JPY gets refunded against 5000 INR.
 * @param refundable false when the payment is in no state to be refunded at all, whatever the
 *     amounts say. Computed by the adapter from Payment's own status, so this record carries a
 *     decision rather than a status Refund would have to interpret -- interpreting it would mean
 *     knowing Payment's enum, which is the import the boundary forbids.
 */
public record RefundablePayment(
    String paymentIntentId,
    long capturedAmountMinor,
    String currency,
    boolean refundable
) {
}
