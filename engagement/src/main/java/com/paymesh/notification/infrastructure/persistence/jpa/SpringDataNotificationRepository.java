package com.paymesh.notification.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Spring Data access to notifications. Not referenced outside this package. */
public interface SpringDataNotificationRepository
    extends JpaRepository<NotificationJpaEntity, String> {

    boolean existsBySourceEventId(String sourceEventId);

    /**
     * The dispatcher's candidate list: PENDING ids, oldest first. Partial index
     * {@code idx_notifications_pending} backs it; terminal rows accumulate forever and are never
     * wanted here.
     */
    @Query("""
        select n.notificationId from NotificationJpaEntity n
         where n.status = 'PENDING'
         order by n.createdAt, n.notificationId
        """)
    List<String> findPendingIds(Pageable page);

    /**
     * Claims one row: {@code SELECT ... FOR UPDATE SKIP LOCKED}, status re-checked. {@code SKIP
     * LOCKED} via Hibernate's {@code -2} timeout. Empty means another dispatcher holds it or it is no
     * longer PENDING -- both no-ops.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        select n from NotificationJpaEntity n
         where n.notificationId = :id and n.status = 'PENDING'
        """)
    Optional<NotificationJpaEntity> findPendingForUpdate(@Param("id") String notificationId);
}
