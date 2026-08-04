package com.paymesh.reconciliation.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One UTC day of a provider's own truth, in this module's vocabulary (ADR-026).
 *
 * <h2>WHY THIS IS NOT THE SIMULATOR'S {@code ReconciliationReport}</h2>
 *
 * It would be one import shorter, and it would be wrong twice over.
 * <p>
 * {@code ModuleBoundaryTest.noCapabilityImportsTheSimulator} has an empty allowlist in both
 * directions and it is not an oversight: SDD 13.2 says the simulator owns no PayMesh state, and its
 * only influence must be HTTP. A reconciliation job that imported its report type could not run
 * against any other provider, and the simulator could no longer be removed from a deployment --
 * which is the first thing anyone would want to do with it.
 * <p>
 * The deeper reason is that this is a <b>wire contract, restated rather than shared</b>, exactly as
 * {@code CallbackBody} restates {@code ProviderCallbackRequest} on the other side of the same
 * boundary. A second provider's file will not have the simulator's field names, and the place that
 * difference belongs is an adapter, not a job.
 *
 * <h2>The fields are what a reconciliation can actually act on</h2>
 *
 * Deliberately narrower than the simulator's response. The behaviour token, the request hash and the
 * idempotency key are all absent: they are how the provider was ASKED to behave, and reconciliation
 * is only ever interested in what it DID.
 *
 * @param date the UTC day this covers
 */
public record ProviderDayReport(LocalDate date, List<Payment> payments, List<Refund> refunds) {

    public ProviderDayReport {
        payments = payments == null ? List.of() : List.copyOf(payments);
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
    }

    /**
     * One payment as the provider records it.
     *
     * @param callbackReference the string PayMesh gave the provider when the payment was created,
     *                          which is PayMesh's own payment intent id. <b>Null is the unmatchable
     *                          case</b> -- a payment created by something other than PayMesh, or by
     *                          a caller that supplied no reference -- and the job reports it rather
     *                          than guessing.
     * @param providerPaymentId the provider's own id. The fallback resolution path, and what a
     *                          human uses to look the row up at the provider.
     * @param status            the provider's status vocabulary, NOT PayMesh's. Kept as the raw
     *                          string: this module does not own the provider's enum and a value it
     *                          has never heard of must survive to be reported rather than crash the
     *                          parse.
     */
    public record Payment(
        String callbackReference,
        String providerPaymentId,
        String status,
        long amountMinor,
        long capturedAmountMinor,
        String failureCode,
        String failureMessage,
        Instant updatedAt
    ) {
    }

    /**
     * One refund as the provider records it.
     *
     * @param callbackReference PayMesh's refund id, echoed back (ADR-026 added the column that
     *                          carries it). Null on every row written before that, which is why the
     *                          job treats an unresolvable refund as a reportable fact rather than an
     *                          error.
     */
    public record Refund(
        String callbackReference,
        String providerRefundId,
        String providerPaymentId,
        String status,
        long amountMinor,
        String failureCode,
        String failureMessage,
        Instant updatedAt
    ) {
    }
}
