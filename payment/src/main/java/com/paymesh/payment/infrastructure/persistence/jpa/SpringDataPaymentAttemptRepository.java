package com.paymesh.payment.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data access to the payment_attempts table.
 * <p>
 * findById(String) is inherited from JpaRepository but is NOT used by the adapter and must not be:
 * it resolves an attempt by id alone, with no tenant predicate. Every merchant-facing method here
 * names merchantId first, so the generated SQL always carries "where merchant_id = ?".
 */
public interface SpringDataPaymentAttemptRepository
    extends JpaRepository<PaymentAttemptJpaEntity, String> {

    long countByMerchantIdAndPaymentIntentId(String merchantId, String paymentIntentId);

    /** The most recent try -- the one a provider callback is about. */
    Optional<PaymentAttemptJpaEntity> findFirstByMerchantIdAndPaymentIntentIdOrderByAttemptNumberDesc(
        String merchantId, String paymentIntentId
    );

    /**
     * THE ONE QUERY HERE THAT DOES NOT NAME A MERCHANT, and the exception is the same one
     * {@code pk_provider_callbacks} rests on: a provider's reference is provider-global, and the
     * merchant is DERIVED from the attempt it names rather than supplied by the caller. Scoping this
     * by merchant would mean taking a tenant from an unauthenticated caller.
     * <p>
     * {@code uq_payment_attempts_provider_reference} is what makes the answer unique, which is why
     * V9 made that index unique rather than merely selective.
     */
    Optional<PaymentAttemptJpaEntity> findByProviderAndProviderReference(
        String provider, String providerReference
    );

    /**
     * THE MONOTONIC EVENT CLOCK, ACROSS ALL OF AN INTENT'S ATTEMPTS (ADR-012).
     * <p>
     * A MAX rather than a read of the latest attempt's column, and the difference is load-bearing:
     * re-confirming after REQUIRES_ACTION opens a NEW attempt whose clock is null, so reading the
     * latest attempt alone would wave through a stale event from the previous attempt -- which is
     * precisely the case the state machine cannot refuse, because inside that cycle the stale
     * transition is a legal one.
     */
    @Query("""
        select max(a.lastProviderEventAt) from PaymentAttemptJpaEntity a
        where a.merchantId = :merchantId and a.paymentIntentId = :paymentIntentId
        """)
    Optional<Instant> maxProviderEventAt(
        @Param("merchantId") String merchantId,
        @Param("paymentIntentId") String paymentIntentId
    );
}
