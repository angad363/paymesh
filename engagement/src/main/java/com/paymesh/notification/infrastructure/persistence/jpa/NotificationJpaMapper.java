package com.paymesh.notification.infrastructure.persistence.jpa;

import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;
import com.paymesh.notification.domain.NotificationStatus;
import com.paymesh.shared.tenant.MerchantId;

/** Between the {@code notifications} row and the domain aggregate. ADR-004: never the same type. */
final class NotificationJpaMapper {

    private NotificationJpaMapper() {
    }

    static NotificationJpaEntity toEntity(Notification notification) {
        return new NotificationJpaEntity(
            notification.id().value(),
            notification.merchantId().value(),
            notification.sourceEventId(),
            notification.eventType(),
            notification.subject(),
            notification.body(),
            notification.status().name(),
            notification.attemptCount(),
            notification.lastError(),
            notification.createdAt(),
            notification.updatedAt(),
            notification.sentAt()
        );
    }

    static Notification toDomain(NotificationJpaEntity entity) {
        return Notification.reconstitute(
            NotificationId.from(entity.notificationId()),
            MerchantId.from(entity.merchantId()),
            entity.sourceEventId(),
            entity.eventType(),
            entity.subject(),
            entity.body(),
            NotificationStatus.valueOf(entity.status()),
            entity.attemptCount(),
            entity.lastError(),
            entity.createdAt(),
            entity.updatedAt(),
            entity.sentAt()
        );
    }
}
