package com.paymesh.payment.api;

import com.paymesh.payment.domain.ProviderCallbackOutcome;

/**
 * What PayMesh did about a delivery, and nothing else.
 * <p>
 * Deliberately not the intent's resulting status. This response goes to a provider, not to the
 * merchant who owns the payment, and echoing an intent's state to a caller authenticated only by a
 * shared secret would make the endpoint a lookup for anyone holding it. The outcome says whether the
 * provider needs to do anything, which is the only question it asked.
 *
 * @param outcome APPLIED, IGNORED_STALE, IGNORED_TERMINAL or DUPLICATE -- all four with a 200.
 */
public record ProviderCallbackResponse(String outcome) {

    public static ProviderCallbackResponse of(ProviderCallbackOutcome outcome) {
        return new ProviderCallbackResponse(outcome.name());
    }
}
