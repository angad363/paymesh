package com.paymesh.payment.application;

/**
 * The requested amount or currency is not the order's.
 * <p>
 * An intent collects exactly its order's obligation -- no more, and no less. Together with the one
 * live intent per order rule that makes overpayment structurally impossible rather than merely
 * constrained, and it is why split payments are out of scope in this version.
 * <p>
 * Safe to be specific here: the caller already owns the order, so nothing is disclosed that they
 * could not read from it directly.
 */
public class PaymentAmountMismatchException extends RuntimeException {
    public PaymentAmountMismatchException(long requestedAmountMinor, String requestedCurrency, long orderAmountMinor, String orderCurrency) {
        super("Payment must be for the order's exact amount: requested "
            + requestedAmountMinor + " " + requestedCurrency
            + ", order is " + orderAmountMinor + " " + orderCurrency);
    }
}
