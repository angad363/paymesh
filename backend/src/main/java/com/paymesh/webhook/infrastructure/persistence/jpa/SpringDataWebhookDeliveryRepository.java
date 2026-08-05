package com.paymesh.webhook.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data access to webhook_deliveries. Not referenced outside this package. */
public interface SpringDataWebhookDeliveryRepository
    extends JpaRepository<WebhookDeliveryJpaEntity, String> {

    Optional<WebhookDeliveryJpaEntity> findByMerchantIdAndDeliveryId(
        String merchantId, String deliveryId
    );

    boolean existsByWebhookEventIdAndEndpointId(String webhookEventId, String endpointId);

    List<WebhookDeliveryJpaEntity> findByMerchantIdAndEndpointIdOrderByCreatedAtDesc(
        String merchantId, String endpointId, Limit limit
    );

    /**
     * The candidate rows, unlocked. Backed by {@code idx_webhook_deliveries_due}, which is partial
     * on {@code status = 'PENDING'} because terminal rows accumulate forever.
     */
    @Query("""
        select d from WebhookDeliveryJpaEntity d
         where d.status = 'PENDING' and d.nextAttemptAt <= :now
         order by d.nextAttemptAt, d.createdAt, d.deliveryId
        """)
    List<WebhookDeliveryJpaEntity> findDue(@Param("now") Instant now, Pageable page);

    /**
     * Claims one row: {@code SELECT ... FOR UPDATE SKIP LOCKED}, status re-checked.
     * <p>
     * {@code SKIP LOCKED} via Hibernate's {@code -2} timeout encoding. Empty means another
     * dispatcher holds the row or it stopped being PENDING since the candidate list was built --
     * both no-ops. Plain {@code FOR UPDATE} would queue the second dispatcher behind the first
     * one's HTTP call, which is the whole thing this is avoiding.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        select d from WebhookDeliveryJpaEntity d
         where d.deliveryId = :id and d.status = 'PENDING'
        """)
    Optional<WebhookDeliveryJpaEntity> findPendingForUpdate(@Param("id") String deliveryId);
}
