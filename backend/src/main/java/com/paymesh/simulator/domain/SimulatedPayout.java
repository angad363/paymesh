package com.paymesh.simulator.domain;

import java.time.Instant;

/**
 * One payout the provider was asked to make. SDD 13.4.
 *
 * <h2>DETERMINISTIC ON THE DESTINATION, EXACTLY AS PAYMENTS ARE ON THE TOKEN</h2>
 *
 * {@code acct_sim_fail} is returned by the bank; anything else is paid. The same shape as
 * {@code tok_sim_decline}, and for the same reason -- a test that wants a failed payout must be
 * able to ask for one, and a percentage-based failure injection would make the suite flaky rather
 * than realistic (ADR-017 §5).
 *
 * <h2>PayMesh's reference is the idempotency key, and it is the provider's rule</h2>
 *
 * {@code uq_provider_payouts_external_reference} makes a resubmission return the original row
 * rather than moving money a second time. That is what allows PayMesh's submission loop to retry at
 * all, and it lives here rather than there because a provider that cannot deduplicate is a provider
 * you cannot safely retry against.
 */
public record SimulatedPayout(
    SimulatedPayoutId providerPayoutId,
    String externalReference,
    String destination,
    long amountMinor,
    String currency,
    SimulatedPayoutStatus status,
    String failureCode,
    Instant createdAt,
    Instant updatedAt
) {

    /** The one destination that is refused. Everything else is paid. */
    public static final String FAILING_DESTINATION = "acct_sim_fail";

    private static final String RETURN_CODE = "ACCOUNT_CLOSED";

    public SimulatedPayout {
        if (providerPayoutId == null) {
            throw new IllegalArgumentException("A simulated payout needs an identifier");
        }

        if (externalReference == null || externalReference.isBlank()) {
            throw new IllegalArgumentException("A simulated payout needs the caller's reference");
        }

        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("A simulated payout needs a destination");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A simulated payout moves a positive amount");
        }

        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("A simulated payout needs an ISO 4217 currency");
        }

        if (status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("A simulated payout needs a status and timestamps");
        }
    }

    public static SimulatedPayout accept(
        String externalReference,
        String destination,
        long amountMinor,
        String currency,
        Instant now
    ) {
        boolean returned = FAILING_DESTINATION.equalsIgnoreCase(destination.strip());

        return new SimulatedPayout(
            SimulatedPayoutId.generate(),
            externalReference,
            destination,
            amountMinor,
            currency.strip().toUpperCase(),
            returned ? SimulatedPayoutStatus.RETURNED : SimulatedPayoutStatus.PAID,
            returned ? RETURN_CODE : null,
            now,
            now
        );
    }

    public boolean wasPaid() {
        return status == SimulatedPayoutStatus.PAID;
    }
}
