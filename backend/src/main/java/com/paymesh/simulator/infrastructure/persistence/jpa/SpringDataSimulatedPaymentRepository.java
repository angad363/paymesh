package com.paymesh.simulator.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data's half of the payment adapter. Not referenced outside this package. */
public interface SpringDataSimulatedPaymentRepository
    extends JpaRepository<SimulatedPaymentJpaEntity, String> {

    Optional<SimulatedPaymentJpaEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * PESSIMISTIC_WRITE, which Hibernate renders as {@code SELECT ... FOR UPDATE}.
     * <p>
     * Capture and refund both need it. Two concurrent refunds that each read a refundable balance of
     * 500 would both pass the aggregate's check, and the loser would then die on
     * {@code ck_provider_payments_refunded} -- correct, but a 500 where a 422 was available.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SimulatedPaymentJpaEntity p where p.providerPaymentId = :id")
    Optional<SimulatedPaymentJpaEntity> findByIdForUpdate(@Param("id") String providerPaymentId);

    /** Half-open, so nothing is counted on two days and nothing falls between them. */
    @Query("""
        select p from SimulatedPaymentJpaEntity p
         where p.createdAt >= :from and p.createdAt < :to
         order by p.createdAt, p.providerPaymentId
        """)
    List<SimulatedPaymentJpaEntity> findCreatedBetween(
        @Param("from") Instant fromInclusive,
        @Param("to") Instant toExclusive
    );
}
