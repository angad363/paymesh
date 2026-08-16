package com.paymesh.notification.application;

import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;

import java.util.List;
import java.util.Optional;

/** The persistence port for {@code notifications}. Implemented in {@code infrastructure}. */
public interface NotificationRepository {

    /**
     * Writes the notification unless one already exists for its source event.
     *
     * @return {@code true} if this call wrote it, {@code false} if a row for the same
     *     {@code sourceEventId} was already present -- which is how a redelivered outbox event
     *     becomes a no-op. The unique constraint is the real guard; this check just avoids the throw.
     */
    boolean saveIfAbsent(Notification notification);

    /** Persists a state change on an existing notification (PENDING -> SENT | FAILED). */
    Notification save(Notification notification);

    Optional<Notification> findById(NotificationId id);

    /**
     * The dispatcher's candidate list: PENDING notification ids, oldest first, capped at
     * {@code limit}. RAW STRINGS, so nothing is mapped or parsed before the per-item transaction --
     * the open-item-2 lesson (docs/project-status.md) applied here from the start.
     */
    List<String> findDue(int limit);

    /**
     * Claims one PENDING notification with {@code FOR UPDATE SKIP LOCKED}. Empty means another
     * dispatcher holds it or it is no longer PENDING -- both no-ops.
     */
    Optional<Notification> claim(NotificationId id);
}
