package com.paymesh.settlement.api;

import com.paymesh.settlement.application.RecordPayoutCallbackService;

/**
 * What PayMesh did with a payout callback.
 *
 * <p>The status code is the retry signal and the body is the detail, exactly as ADR-012 §6 sets it
 * for provider callbacks: a 2xx means "stop retrying", and this says which kind of stop it was.
 */
public record PayoutCallbackResponse(String outcome) {

    public static PayoutCallbackResponse of(RecordPayoutCallbackService.Outcome outcome) {
        return new PayoutCallbackResponse(outcome.name());
    }
}
