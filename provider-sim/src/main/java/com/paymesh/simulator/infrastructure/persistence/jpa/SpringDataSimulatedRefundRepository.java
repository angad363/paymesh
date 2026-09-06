package com.paymesh.simulator.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data's half of the refund adapter. Not referenced outside this package. */
public interface SpringDataSimulatedRefundRepository
    extends JpaRepository<SimulatedRefundJpaEntity, String> {

    Optional<SimulatedRefundJpaEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("""
        select r from SimulatedRefundJpaEntity r
         where r.createdAt >= :from and r.createdAt < :to
         order by r.createdAt, r.providerRefundId
        """)
    List<SimulatedRefundJpaEntity> findCreatedBetween(
        @Param("from") Instant fromInclusive,
        @Param("to") Instant toExclusive
    );
}
