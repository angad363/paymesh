package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.Payout;

/**
 * Hands one payout to the provider that moves the money.
 *
 * <p>An interface with one implementation, which the house rules discourage, and it earns its place
 * for the reason {@code CallbackSender} does: the application layer must not do HTTP, and this is
 * the seam a test replaces to drive a refusal without a provider being unreachable for real.
 *
 * <p><b>Submission is not confirmation.</b> This returns the provider's reference for a payout it
 * has accepted; whether the money landed arrives later, as a signed callback. Treating a 2xx here
 * as "paid" would post {@code BANK_CASH} on PayMesh's own optimism.
 */
@FunctionalInterface
public interface PayoutGateway {

    /**
     * @return the provider's own reference for this payout
     * @throws PayoutSubmissionFailedException when the provider refused it or could not be reached.
     *     The caller retries within a budget; it does not distinguish the two, because a refusal
     *     that is really a timeout is indistinguishable from this side
     */
    String submit(Payout payout);
}
