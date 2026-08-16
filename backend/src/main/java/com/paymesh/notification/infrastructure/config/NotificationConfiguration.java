package com.paymesh.notification.infrastructure.config;

import com.paymesh.notification.application.GetNotificationService;
import com.paymesh.notification.application.NotificationRepository;
import com.paymesh.notification.application.NotificationSender;
import com.paymesh.notification.application.RecordNotificationService;
import com.paymesh.notification.application.SendPendingNotificationsService;
import com.paymesh.notification.infrastructure.events.NotificationEventHandler;
import com.paymesh.notification.infrastructure.events.NotificationTemplates;
import com.paymesh.notification.infrastructure.persistence.jpa.JpaNotificationRepository;
import com.paymesh.notification.infrastructure.persistence.jpa.SpringDataNotificationRepository;
import com.paymesh.notification.infrastructure.schedule.NotificationDispatcher;
import com.paymesh.notification.infrastructure.send.SimulatedNotificationSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Manual wiring for the Notification capability (ADR-002, java-coding-conventions §13). Every service
 * and adapter below is a plain {@code final} class with no Spring annotation; this is the only file
 * that knows they are beans.
 */
@Configuration
@EnableConfigurationProperties(NotificationDispatchProperties.class)
public class NotificationConfiguration {

    @Bean
    NotificationRepository notificationRepository(SpringDataNotificationRepository notifications) {
        return new JpaNotificationRepository(notifications);
    }

    @Bean
    NotificationTemplates notificationTemplates() {
        return new NotificationTemplates();
    }

    @Bean
    RecordNotificationService recordNotificationService(
        NotificationRepository notifications, Clock clock
    ) {
        return new RecordNotificationService(notifications, clock);
    }

    @Bean
    NotificationSender notificationSender() {
        return new SimulatedNotificationSender();
    }

    @Bean
    GetNotificationService getNotificationService(NotificationRepository notifications) {
        return new GetNotificationService(notifications);
    }

    @Bean
    SendPendingNotificationsService sendPendingNotificationsService(
        NotificationRepository notifications,
        NotificationSender sender,
        TransactionTemplate transactions,
        NotificationDispatchProperties properties,
        Clock clock
    ) {
        return new SendPendingNotificationsService(
            notifications, sender, transactions, clock,
            properties.batchSize(), properties.maxAttempts()
        );
    }

    /**
     * THREE HANDLER BEANS, ONE CLASS. {@code EventDispatcher} collects every {@code EventHandler}
     * bean and indexes it by type, so subscribing to a fourth event is a line here plus a template --
     * and the handler's constructor refuses a type the templates do not know, which makes the pairing
     * a startup failure rather than a per-event one.
     */
    @Bean
    NotificationEventHandler paymentSucceededNotificationHandler(
        NotificationTemplates templates, RecordNotificationService record
    ) {
        return new NotificationEventHandler("payment.succeeded", templates, record);
    }

    @Bean
    NotificationEventHandler paymentFailedNotificationHandler(
        NotificationTemplates templates, RecordNotificationService record
    ) {
        return new NotificationEventHandler("payment.failed", templates, record);
    }

    @Bean
    NotificationEventHandler refundSucceededNotificationHandler(
        NotificationTemplates templates, RecordNotificationService record
    ) {
        return new NotificationEventHandler("refund.succeeded", templates, record);
    }

    /**
     * THE TIMER, ABSENT UNDER {@code dev} like every other timer in this codebase.
     * {@code SendPendingNotificationsService} is an ordinary bean regardless, so tests call
     * {@code dispatch()} directly rather than waiting for a tick.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.notification.dispatch", name = "enabled", matchIfMissing = true
    )
    NotificationDispatcher notificationDispatcher(SendPendingNotificationsService sendPending) {
        return new NotificationDispatcher(sendPending);
    }
}
