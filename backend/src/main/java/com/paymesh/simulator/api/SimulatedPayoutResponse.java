package com.paymesh.simulator.api;

import com.paymesh.simulator.domain.SimulatedPayout;

/**
 * What the provider says on accepting a payout.
 *
 * <p>The status is the provider's own decision and it is already made -- this simulator has no bank
 * to wait for -- but the CALLER must not act on it. The authoritative statement is the signed
 * callback that follows, which is what PayMesh's ledger posts from. Returning it here at all is a
 * debugging affordance, and it is named the way a bank names it (PAID / RETURNED) rather than the
 * way PayMesh does, so nobody is tempted to map it straight through.
 */
public record SimulatedPayoutResponse(
    String providerPayoutId, String externalReference, String status
) {

    public static SimulatedPayoutResponse from(SimulatedPayout payout) {
        return new SimulatedPayoutResponse(
            payout.providerPayoutId().value(), payout.externalReference(), payout.status().name()
        );
    }
}
