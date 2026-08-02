package com.paymesh.refund.domain;

/**
 * An action asked of a refund that is not in a state to accept it.
 * <p>
 * One exception for every illegal transition rather than one per verb, because the caller's answer
 * is the same in all of them -- 409, and a sentence saying where the refund actually is. The
 * distinctions that matter are in the message.
 */
public final class RefundNotInStateException extends IllegalStateException {

    private final RefundId refundId;
    private final RefundStatus actual;
    private final RefundStatus required;

    public RefundNotInStateException(
        RefundId refundId,
        RefundStatus actual,
        RefundStatus required,
        String action
    ) {
        super(
            "Refund " + refundId.value() + " is " + actual + " and cannot be " + action
                + "; that requires " + required
        );

        this.refundId = refundId;
        this.actual = actual;
        this.required = required;
    }

    public RefundId refundId() {
        return refundId;
    }

    public RefundStatus actual() {
        return actual;
    }

    public RefundStatus required() {
        return required;
    }
}
