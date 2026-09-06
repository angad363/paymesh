package com.paymesh.simulator.application;

/**
 * @param externalReference the caller's own id for this payout, and the idempotency key. The
 *     provider is the party that has to make a resubmission safe, so this is a rule here rather
 *     than a hope there
 */
public record CreateSimulatedPayoutCommand(
    String externalReference, String destination, long amountMinor, String currency
) {
}
