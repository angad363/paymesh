package com.paymesh.refund.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.refund.application.CancelRefundService;
import com.paymesh.refund.application.CreateRefundService;
import com.paymesh.refund.application.GetRefundService;
import com.paymesh.refund.application.ListRefundsService;
import com.paymesh.refund.application.PaymentLookup;
import com.paymesh.refund.application.RecordRefundCallbackService;
import com.paymesh.refund.application.RefundCallbackRepository;
import com.paymesh.refund.application.RefundRepository;
import com.paymesh.refund.application.RefundStateHistoryRepository;
import com.paymesh.refund.infrastructure.payment.PaymentModuleLookup;
import com.paymesh.refund.infrastructure.persistence.jpa.JpaRefundCallbackRepository;
import com.paymesh.refund.infrastructure.persistence.jpa.JpaRefundRepository;
import com.paymesh.refund.infrastructure.persistence.jpa.JpaRefundStateHistoryRepository;
import com.paymesh.shared.outbox.application.EventHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beans are wired by hand rather than component-scanned, so "is it wired" is a real question.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class RefundConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresEveryRepositoryAdapter() {
        assertThat(context.getBean(RefundRepository.class)).isInstanceOf(JpaRefundRepository.class);
        assertThat(context.getBean(RefundStateHistoryRepository.class))
            .isInstanceOf(JpaRefundStateHistoryRepository.class);
        assertThat(context.getBean(RefundCallbackRepository.class))
            .isInstanceOf(JpaRefundCallbackRepository.class);
    }

    @Test
    void wiresTheApplicationServices() {
        assertThat(context.getBean(CreateRefundService.class)).isNotNull();
        assertThat(context.getBean(GetRefundService.class)).isNotNull();
        assertThat(context.getBean(ListRefundsService.class)).isNotNull();
        assertThat(context.getBean(CancelRefundService.class)).isNotNull();
        assertThat(context.getBean(RecordRefundCallbackService.class)).isNotNull();
    }

    /** Refund's one permitted reach into another capability (ADR-008, ADR-019). */
    @Test
    void wiresThePaymentPortToItsAdapter() {
        assertThat(context.getBean(PaymentLookup.class)).isInstanceOf(PaymentModuleLookup.class);
    }

    /**
     * EVERY CONSUMER OF {@code refund.succeeded}, and their names must differ.
     * <p>
     * {@code EventDispatcher} refuses two handlers of one event type that share a consumer name, so
     * a context that starts has already proved it -- but only if every bean exists, which is what
     * this actually pins. A missing handler bean starts perfectly and simply never runs.
     * <p>
     * Four now: Webhook joined the Ledger and Payment in the PR that built it (ADR-028), Notification
     * in ADR-033.
     */
    @Test
    void subscribesTheLedgerPaymentWebhookNotificationAndReportingToRefundSucceeded() {
        assertThat(context.getBeansOfType(EventHandler.class).values().stream()
            .filter(handler -> handler.eventType().equals("refund.succeeded"))
            .map(EventHandler::consumerName)
            .toList())
            .containsExactlyInAnyOrder(
                "ledger.refund-succeeded", "payment.refund-succeeded", "webhook.refund.succeeded",
                "notification.refund.succeeded", "reporting.refund.succeeded"
            );
    }

    /** The refund callback route has its own signature filter instance (ADR-019). */
    @Test
    void registersASignatureFilterForTheRefundCallbackRoute() {
        assertThat(context.containsBean("refundCallbackSignatureFilterRegistration")).isTrue();
    }

    /** Its secret is its own property, separable from the payment one. */
    @Test
    void readsItsOwnCallbackSecret() {
        assertThat(context.getBean(RefundProperties.class).callbackSecret())
            .isEqualTo("dev-only-insecure-refund-callback-secret-change-me");
    }
}
