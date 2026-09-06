package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedPaymentId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The port the simulator's payment storage answers.
 * <p>
 * <b>Every method here is unscoped, and that is correct exactly once in this codebase.</b> Every
 * other repository in PayMesh takes a {@code MerchantId} as its authorization, because every other
 * one serves data a merchant supplied. A provider has never been told that tenants exist: it holds
 * one API credential, it serves one caller, and there is no merchant column on any of its tables to
 * scope by. An implementer adding one would be inventing an authorization decision the simulator has
 * no basis to make.
 */
public interface SimulatedPaymentRepository {

    SimulatedPayment save(SimulatedPayment payment);

    Optional<SimulatedPayment> findById(SimulatedPaymentId providerPaymentId);

    /** The provider-side idempotency lookup. The unique constraint is the guard; this is the manners. */
    Optional<SimulatedPayment> findByIdempotencyKey(String idempotencyKey);

    /**
     * Locks the payment row for the duration of the caller's transaction.
     * <p>
     * Refunds need it: two concurrent refunds that both read a refundable balance of 500 would both
     * pass the application check and one would then die on
     * {@code ck_provider_payments_refunded} -- correct, but a 500 where a 422 was available.
     */
    Optional<SimulatedPayment> findByIdForUpdate(SimulatedPaymentId providerPaymentId);

    /** Everything the provider did in a half-open UTC window. The reconciliation export's query. */
    List<SimulatedPayment> findCreatedBetween(Instant fromInclusive, Instant toExclusive);
}
