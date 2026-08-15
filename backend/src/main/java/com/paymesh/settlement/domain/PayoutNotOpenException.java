package com.paymesh.settlement.domain;

/** A payout that already reached PAID or FAILED being answered for again. */
public final class PayoutNotOpenException extends RuntimeException {

    private final PayoutId payoutId;
    private final PayoutStatus status;

    public PayoutNotOpenException(PayoutId payoutId, PayoutStatus status) {
        super("Payout " + payoutId.value() + " is already " + status);

        this.payoutId = payoutId;
        this.status = status;
    }

    public PayoutId payoutId() {
        return payoutId;
    }

    public PayoutStatus status() {
        return status;
    }
}
