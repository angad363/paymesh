package com.paymesh.notification.infrastructure.persistence.jpa;

import com.paymesh.notification.application.NotificationRepository;
import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed {@link NotificationRepository}. */
public final class JpaNotificationRepository implements NotificationRepository {

    private final SpringDataNotificationRepository notifications;

    public JpaNotificationRepository(SpringDataNotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * Check-then-insert, and the check is not the guard -- {@code uq_notifications_source_event} is.
     * This runs inside the event dispatcher's transaction, which the inbox has already serialized per
     * (consumer, event), so the two callers that could race here cannot both run. If that ever
     * changes the unique constraint throws, the handler rolls back, and the retry finds the row.
     */
    @Override
    public boolean saveIfAbsent(Notification notification) {
        if (notifications.existsBySourceEventId(notification.sourceEventId())) {
            return false;
        }

        notifications.saveAndFlush(NotificationJpaMapper.toEntity(notification));

        return true;
    }

    @Override
    public Notification save(Notification notification) {
        return NotificationJpaMapper.toDomain(
            notifications.saveAndFlush(NotificationJpaMapper.toEntity(notification))
        );
    }

    @Override
    public Optional<Notification> findById(NotificationId id) {
        return notifications.findById(id.value()).map(NotificationJpaMapper::toDomain);
    }

    @Override
    public List<String> findDue(int limit) {
        return notifications.findPendingIds(PageRequest.ofSize(limit));
    }

    @Override
    public Optional<Notification> claim(NotificationId id) {
        return notifications.findPendingForUpdate(id.value()).map(NotificationJpaMapper::toDomain);
    }
}
