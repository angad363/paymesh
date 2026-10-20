package com.paymesh.refund.api;

import com.paymesh.refund.domain.RefundCallbackOutcome;

/**
 * What PayMesh did with the callback. Always a 200 -- see {@link RefundCallbackOutcome} for why a
 * duplicate and a stale delivery are not the provider's error.
 */
public record RefundCallbackResponse(String result) {

    public static RefundCallbackResponse of(RefundCallbackOutcome outcome) {
        return new RefundCallbackResponse(outcome.name());
    }
}
