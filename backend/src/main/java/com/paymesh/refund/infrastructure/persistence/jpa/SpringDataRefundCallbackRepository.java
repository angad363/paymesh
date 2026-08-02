package com.paymesh.refund.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SpringDataRefundCallbackRepository
    extends JpaRepository<RefundCallbackJpaEntity, RefundCallbackJpaId> {

    boolean existsByProviderAndExternalEventId(String provider, String externalEventId);

    /**
     * The newest provider clock among callbacks already recorded for this refund.
     * <p>
     * MAX over occurred_at rather than "the last row inserted": callbacks arrive out of order,
     * which is the entire reason the staleness check exists.
     */
    @Query("""
        select max(c.occurredAt) from RefundCallbackJpaEntity c
        where c.refundId = :refundId
        """)
    Optional<Instant> latestOccurredAt(@Param("refundId") String refundId);
}
