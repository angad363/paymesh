package com.paymesh.notification.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.notification.application.GetNotificationService;
import com.paymesh.notification.application.NotificationRepository;
import com.paymesh.notification.application.NotificationSender;
import com.paymesh.notification.application.RecordNotificationService;
import com.paymesh.notification.application.SendPendingNotificationsService;
import com.paymesh.notification.infrastructure.events.NotificationEventHandler;
import com.paymesh.notification.infrastructure.persistence.jpa.JpaNotificationRepository;
import com.paymesh.notification.infrastructure.schedule.NotificationDispatcher;
import com.paymesh.notification.infrastructure.send.SimulatedNotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beans are wired by hand, not component-scanned, so "is it wired" is a real question answered here.
 * A missing {@code @Bean} is otherwise a startup failure that surfaces in whichever test boots a
 * context first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class NotificationConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresTheRepositoryAdapterAndServices() {
        assertThat(context.getBean(NotificationRepository.class))
            .isInstanceOf(JpaNotificationRepository.class);
        assertThat(context.getBean(NotificationSender.class))
            .isInstanceOf(SimulatedNotificationSender.class);
        assertThat(context.getBean(RecordNotificationService.class)).isNotNull();
        assertThat(context.getBean(GetNotificationService.class)).isNotNull();
        assertThat(context.getBean(SendPendingNotificationsService.class)).isNotNull();
    }

    /** One handler per subscribed type, so the dispatcher can index all three. */
    @Test
    void subscribesToEveryPublishedType() {
        List<String> types = context.getBeansOfType(NotificationEventHandler.class).values().stream()
            .map(NotificationEventHandler::eventType)
            .toList();

        assertThat(types)
            .containsExactlyInAnyOrder("payment.succeeded", "payment.failed", "refund.succeeded");
    }

    /**
     * THE ASSERTION THIS CLASS EXISTS FOR. {@code dev} is the profile every {@code @SpringBootTest}
     * runs under; a timer flipping notification rows to SENT while another test asserts on them is a
     * flake. {@code @ConditionalOnProperty} makes the bean ABSENT rather than present-and-idle, so
     * this is a bean-count assertion and not a flag check.
     */
    @Test
    void doesNotRegisterTheDispatchTimerUnderTheDevelopmentProfile() {
        assertThat(context.getBeanNamesForType(NotificationDispatcher.class))
            .as("paymesh.notification.dispatch.enabled is false in application-dev.yaml")
            .isEmpty();
    }

    @Test
    void bindsTheDispatchTuning() {
        NotificationDispatchProperties properties =
            context.getBean(NotificationDispatchProperties.class);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.batchSize()).isPositive();
        assertThat(properties.maxAttempts()).isPositive();
    }
}
