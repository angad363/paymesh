package com.paymesh.ledger.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.LedgerAccountRepository;
import com.paymesh.ledger.application.LedgerTransactionRepository;
import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.ledger.infrastructure.events.PaymentSucceededLedgerHandler;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaBalanceRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaLedgerAccountRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaLedgerTransactionRepository;
import com.paymesh.shared.outbox.application.EventHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beans are wired by hand rather than component-scanned, so "is it wired" is a real question and
 * this is where it is answered.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class LedgerConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresEveryRepositoryAdapter() {
        assertThat(context.getBean(LedgerAccountRepository.class))
            .isInstanceOf(JpaLedgerAccountRepository.class);
        assertThat(context.getBean(LedgerTransactionRepository.class))
            .isInstanceOf(JpaLedgerTransactionRepository.class);
        assertThat(context.getBean(BalanceRepository.class))
            .isInstanceOf(JpaBalanceRepository.class);
    }

    @Test
    void wiresTheApplicationServices() {
        assertThat(context.getBean(PostPaymentCapturedService.class)).isNotNull();
        assertThat(context.getBean(GetBalancesService.class)).isNotNull();
    }

    /**
     * THE SUBSCRIPTION ITSELF, and it is the bean whose absence would be silent. Every other missing
     * bean here fails the context; a missing handler bean starts perfectly and simply never posts,
     * and the first sign would be a merchant asking where their balance is.
     */
    @Test
    void subscribesTheLedgerToPaymentSucceeded() {
        PaymentSucceededLedgerHandler handler =
            context.getBean(PaymentSucceededLedgerHandler.class);

        assertThat(handler.eventType()).isEqualTo("payment.succeeded");
        assertThat(handler.consumerName()).isEqualTo("ledger.payment-succeeded");

        assertThat(context.getBeansOfType(EventHandler.class).values())
            .as("the dispatcher is built from every EventHandler bean, so being one is the"
                + " subscription")
            .contains(handler);
    }

    /**
     * EVERY CONSUMER IS PRESENT AND DISTINCT. The dispatcher throws at construction if two handlers
     * of one event type share a consumer name, so a context that starts at all has already proved
     * the names differ -- but only two handlers of the SAME event exercise that, and this was the
     * first branch where there were two.
     * <p>
     * Four now: Webhook subscribed in the PR that built it (ADR-028), Notification in ADR-033. Listed
     * exhaustively rather than with {@code contains}, because the failure this catches is a consumer
     * <b>disappearing</b> from a rename or a lost {@code @Bean}, and a containment assertion would not
     * notice.
     */
    @Test
    void registersEveryConsumerOfPaymentSucceeded() {
        assertThat(context.getBeansOfType(EventHandler.class).values().stream()
            .filter(handler -> handler.eventType().equals("payment.succeeded"))
            .map(EventHandler::consumerName)
            .toList())
            .containsExactlyInAnyOrder(
                "order.payment-succeeded", "ledger.payment-succeeded", "webhook.payment.succeeded",
                "notification.payment.succeeded", "reporting.payment.succeeded"
            );
    }
}
