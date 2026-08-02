package com.paymesh.simulator.domain;

/** Whether the simulated provider agreed to send the money back. */
public enum RefundStatus {

    SUCCEEDED,

    /**
     * Reached when the failure profile's default behaviour is DECLINE.
     * <p>
     * Not the same thing as a refund the simulator refused to accept: asking to refund more than was
     * captured is a 422 and writes no row at all, because that request was never a refund. A FAILED
     * row is one the provider took and then declined, which is a different fact and the one a Refund
     * capability will need to test against.
     */
    FAILED
}
