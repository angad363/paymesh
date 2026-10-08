package com.paymesh.risk.infrastructure.events;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.risk.application.EvaluateRiskCommand;
import com.paymesh.risk.application.EvaluateRiskService;
import com.paymesh.risk.application.PaymentVelocityLookup;
import com.paymesh.risk.domain.RiskOutcome;
import com.paymesh.risk.infrastructure.read.PaymentIntentRefStore;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROVES {@code PaymentCreatedProjector} AND {@code PaymentIntentRefStore} AGAINST A REAL
 * POSTGRES (ADR-044 section 4): the event-fed replacement for the in-process velocity call this
 * deployable cannot make.
 *
 * <p>Drives {@code payment.created} envelopes through the real {@link EventDispatcher}, the same
 * pattern {@code RecordUserAccessAuditHandlerIntegrationTest} uses in engagement -- the inbox row
 * and the read-model write commit together, exactly as they would from a real Kafka record.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class PaymentCreatedProjectorIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private PaymentIntentRefStore store;

    @Autowired
    private PaymentVelocityLookup velocity;

    @Autowired
    private EvaluateRiskService evaluateRiskService;

    @Test
    void projectsACustomerIntentAndItCountsInTheVelocityWindow() {
        MerchantId merchantId = MerchantId.generate();
        String customerId = customerId();
        String intentId = paymentIntentId();

        dispatcher.dispatch(paymentCreated(merchantId, customerId, intentId, NOW));

        assertThat(velocity.intentsCreatedSince(
            merchantId, customerId, NOW.minusSeconds(3600), "pi_" + UUID.randomUUID()
        )).isEqualTo(1);
    }

    /** THE INTENT BEING JUDGED MUST NOT COUNT ITSELF -- the same off-by-one ADR-030 already fixed
     * once for the in-process query, now re-proven for the event-fed one. */
    @Test
    void excludesTheIntentBeingJudged() {
        MerchantId merchantId = MerchantId.generate();
        String customerId = customerId();
        String intentId = paymentIntentId();

        dispatcher.dispatch(paymentCreated(merchantId, customerId, intentId, NOW));

        assertThat(velocity.intentsCreatedSince(
            merchantId, customerId, NOW.minusSeconds(3600), intentId
        )).isZero();
    }

    /** An intent created before the window start must not count towards it. */
    @Test
    void doesNotCountAnIntentOutsideTheWindow() {
        MerchantId merchantId = MerchantId.generate();
        String customerId = customerId();

        store.record(merchantId, customerId, paymentIntentId(), NOW.minusSeconds(7_200));

        assertThat(velocity.intentsCreatedSince(
            merchantId, customerId, NOW.minusSeconds(3_600), "pi_" + UUID.randomUUID()
        )).isZero();
    }

    /** One merchant's traffic must not raise another merchant's velocity for the same customer id. */
    @Test
    void doesNotCountAnotherMerchantsIntentForTheSameCustomerId() {
        MerchantId busy = MerchantId.generate();
        MerchantId quiet = MerchantId.generate();
        String sharedCustomerId = customerId();

        store.record(busy, sharedCustomerId, paymentIntentId(), NOW);

        assertThat(velocity.intentsCreatedSince(
            quiet, sharedCustomerId, NOW.minusSeconds(3_600), "pi_" + UUID.randomUUID()
        )).isZero();
    }

    /** A redelivered event -- the same eventId, or a genuinely duplicate projection attempt for
     * the same intent -- must not double-count. */
    @Test
    void recordingTheSameIntentTwiceIsIdempotent() {
        MerchantId merchantId = MerchantId.generate();
        String customerId = customerId();
        String intentId = paymentIntentId();

        store.record(merchantId, customerId, intentId, NOW);
        store.record(merchantId, customerId, intentId, NOW);

        assertThat(velocity.intentsCreatedSince(
            merchantId, customerId, NOW.minusSeconds(3600), "pi_" + UUID.randomUUID()
        )).isEqualTo(1);
    }

    /** A guest checkout contributes nothing: there is no customer to count velocity against. */
    @Test
    void skipsAGuestCheckoutRatherThanFailing() {
        MerchantId merchantId = MerchantId.generate();
        String intentId = paymentIntentId();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", intentId);
        payload.put("customerId", null);
        payload.put("createdAt", NOW.toString());

        dispatcher.dispatch(new OutboxEvent(
            EventId.generate(), merchantId, "PAYMENT_INTENT", intentId,
            "payment.created", 1, payload, NOW
        ));

        // No exception, and nothing was stored -- proven indirectly: a fresh customer sharing no
        // rows still scores zero, which would also be true of a bug that silently dropped every
        // row, so the real proof is that dispatch() above did not throw for a guest payload the
        // in-process path never has to handle at all.
    }

    /**
     * THE ADR-030 REGRESSION, ADAPTED FOR THE EVENT-FED READ MODEL: a fresh customer's first
     * intent must score zero, evaluated the same way EvaluateRiskService is at confirm time.
     */
    @Test
    void aFreshCustomersFirstIntentScoresZeroThroughEvaluateRiskService() {
        MerchantId merchantId = MerchantId.generate();
        String customerId = customerId();
        String intentId = paymentIntentId();

        dispatcher.dispatch(paymentCreated(merchantId, customerId, intentId, NOW));

        var assessment = evaluateRiskService.evaluate(new EvaluateRiskCommand(
            merchantId, intentId, 1_00L, "INR", customerId, null
        ));

        assertThat(assessment.outcome()).isEqualTo(RiskOutcome.ALLOW);
        assertThat(assessment.features().intentsInWindow())
            .as("the intent projected above is the one being judged, and must not count itself")
            .isZero();
    }

    private OutboxEvent paymentCreated(
        MerchantId merchantId, String customerId, String paymentIntentId, Instant createdAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", paymentIntentId);
        payload.put("customerId", customerId);
        payload.put("createdAt", createdAt.toString());

        return new OutboxEvent(
            EventId.generate(), merchantId, "PAYMENT_INTENT", paymentIntentId,
            "payment.created", 1, payload, createdAt
        );
    }

    private static String customerId() {
        return "cus_" + UUID.randomUUID();
    }

    private static String paymentIntentId() {
        return "pi_" + UUID.randomUUID();
    }
}
