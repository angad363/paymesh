package com.paymesh.refund;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.refund.application.CreateRefundCommand;
import com.paymesh.refund.application.CreateRefundService;
import com.paymesh.refund.infrastructure.events.OrderCapturedAfterCloseHandler;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-049 end to end against a real PostgreSQL: a payment captured against a CANCELLED or EXPIRED
 * order is refunded in full, once, by the event chain alone.
 * <p>
 * Not {@code @Transactional}: the dispatcher's per-handler transaction and the deferred
 * {@code tr_refunds_within_captured} trigger are the things under test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class RefundCapturedAfterCloseIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final long CAPTURED = 4200;

    @Autowired private PublishOutboxEventsService relay;
    @Autowired private EventDispatcher dispatcher;
    @Autowired private OrderRepository orders;
    @Autowired private CreatePaymentIntentService createPaymentIntentService;
    @Autowired private AttachPaymentMethodService attachPaymentMethodService;
    @Autowired private ConfirmPaymentIntentService confirmPaymentIntentService;
    @Autowired private RecordProviderCallbackService paymentCallbacks;
    @Autowired private CreateRefundService createRefundService;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcClient jdbc;

    private record Fixture(MerchantId merchantId, Order order, String intentId) {
    }

    @Test
    void refundsInFullAMoneyCapturedAgainstACancelledOrder() {
        Fixture fixture = capturedAfter(order -> order.cancel("changed my mind", CREATED_AT.plusSeconds(1)));

        drain();

        assertThat(orders.findByOrderId(fixture.merchantId(), fixture.order().orderId()).orElseThrow().status())
            .isEqualTo(OrderStatus.CANCELLED);
        assertThat(eventsFor(fixture, "order.payment_captured_after_close")).isEqualTo(1);
        assertThat(refundsFor(fixture)).containsExactly(CAPTURED);
    }

    @Test
    void refundsInFullAMoneyCapturedAgainstAnExpiredOrder() {
        Fixture fixture = capturedAfter(order -> order.expire(CREATED_AT.plusSeconds(1)));

        drain();

        assertThat(eventsFor(fixture, "order.payment_captured_after_close")).isEqualTo(1);
        assertThat(refundsFor(fixture)).containsExactly(CAPTURED);
    }

    /** Idempotent no-op preserved: a capture against an order that is already PAID refunds nothing. */
    @Test
    void doesNotRefundACaptureAgainstAnOrderThatIsAlreadyPaid() {
        Fixture fixture = capturedAfter(order -> order.markPaid(CAPTURED, CREATED_AT.plusSeconds(1)));

        drain();

        assertThat(eventsFor(fixture, "order.payment_captured_after_close")).isZero();
        assertThat(refundsFor(fixture)).isEmpty();
    }

    /** Redelivery, both past the inbox (same event id) and around it (a fresh event id). */
    @Test
    void aRedeliveredEventDoesNotRefundTwice() {
        Fixture fixture = capturedAfter(order -> order.cancel("changed my mind", CREATED_AT.plusSeconds(1)));
        drain();

        OutboxEvent event = jdbcEventFor(fixture);

        dispatcher.dispatch(event);                       // first claim: headroom guard skips
        dispatcher.dispatch(event);                       // same id: the inbox drops it
        dispatcher.dispatch(withFreshId(event));          // new inbox key: headroom guard drops it

        assertThat(refundsFor(fixture)).containsExactly(CAPTURED);
    }

    /** An operator already refunded in full: the auto-refund finds no head-room and does not double. */
    @Test
    void aManualFullRefundLeavesNoHeadroomForTheAutoRefund() {
        Fixture fixture = capturedAfter(order -> order.cancel("changed my mind", CREATED_AT.plusSeconds(1)));

        createRefundService.create(new CreateRefundCommand(
            fixture.merchantId(), fixture.intentId(), null, "MANUAL-" + UUID.randomUUID(),
            "operator", "usr_1"
        ));

        drain();

        assertThat(eventsFor(fixture, "order.payment_captured_after_close")).isEqualTo(1);
        assertThat(refundsFor(fixture)).containsExactly(CAPTURED);
        assertThat(jdbc.sql("select count(*) from refunds where payment_intent_id = ? and merchant_reference like 'auto-close-%'")
            .params(fixture.intentId()).query(Long.class).single()).isZero();
    }

    /**
     * The first auto-refund FAILED at the provider: head-room is free again, but its
     * {@code auto-close-<intent>} reference is taken. A duplicate event with a fresh inbox key must
     * SKIP on the reference read -- not attempt a create that collides on
     * {@code uq_refunds_merchant_reference}, whose {@code saveAndFlush} would mark the dispatcher's
     * transaction rollback-only and redeliver forever. If this regresses, {@code dispatch} throws.
     */
    @Test
    void aFailedAutoRefundIsNotRetriedIntoAReferenceCollision() {
        Fixture fixture = capturedAfter(order -> order.cancel("changed my mind", CREATED_AT.plusSeconds(1)));
        drain();

        jdbc.sql("update refunds set status = 'FAILED' "
                + "where payment_intent_id = ? and merchant_reference like 'auto-close-%'")
            .params(fixture.intentId()).update();

        dispatcher.dispatch(withFreshId(jdbcEventFor(fixture)));

        assertThat(jdbc.sql("select count(*) from refunds "
                + "where payment_intent_id = ? and merchant_reference like 'auto-close-%'")
            .params(fixture.intentId()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void consumerNameAndEventTypeAreStable() {
        OrderCapturedAfterCloseHandler handler = new OrderCapturedAfterCloseHandler(null, null, null);

        assertThat(handler.consumerName()).isEqualTo("refund.order-captured-after-close");
        assertThat(handler.eventType()).isEqualTo("order.payment_captured_after_close");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private interface OrderClose {
        Order apply(Order pending);
    }

    /**
     * Collects a payment, then closes the order BEFORE payment.succeeded is dispatched, which is the
     * race the defect lives in: the capture lands on an order that is no longer PENDING.
     */
    private Fixture capturedAfter(OrderClose close) {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(), "Paymesh Auto Refund Co", UUID.randomUUID() + "@paymesh.test",
            "IN", "INR", CREATED_AT
        ).activate(CREATED_AT)).merchantId();

        Order order = orders.save(Order.create(
            OrderId.generate(), merchantId, null, "ORDER-" + UUID.randomUUID(),
            CAPTURED, "INR", null, Map.of(), CREATED_AT.plusSeconds(1), CREATED_AT
        ));

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, order.orderId().value(), null, CAPTURED, "INR",
            CaptureMethod.AUTOMATIC, null, Map.of()
        ));

        attachPaymentMethodService.attach(merchantId, intent.paymentIntentId(), PaymentMethodType.CARD);
        confirmPaymentIntentService.confirm(
            new ConfirmPaymentIntentCommand(merchantId, intent.paymentIntentId(), null, null)
        );

        orders.save(close.apply(orders.findByOrderId(merchantId, order.orderId()).orElseThrow()));

        paymentCallbacks.record(new RecordProviderCallbackCommand(
            "SIMULATOR",
            new ProviderEvent(
                "evt-succeed-" + UUID.randomUUID(), PROVIDER_EVENT, intent.paymentIntentId().value(),
                null, ProviderOutcome.SUCCEEDED, null, CAPTURED, null, null, null
            ),
            (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "")
        ));

        return new Fixture(merchantId, order, intent.paymentIntentId().value());
    }

    private void drain() {
        while (relay.publish().published() > 0) {
            // shared container: a pass may also consume another test's backlog
        }
    }

    private long eventsFor(Fixture fixture, String type) {
        return jdbc.sql("select count(*) from outbox_events where merchant_id = ? and event_type = ?")
            .params(fixture.merchantId().value(), type).query(Long.class).single();
    }

    private java.util.List<Long> refundsFor(Fixture fixture) {
        return jdbc.sql("select amount_minor from refunds where payment_intent_id = ? order by created_at")
            .params(fixture.intentId()).query(Long.class).list();
    }

    private OutboxEvent jdbcEventFor(Fixture fixture) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", fixture.order().orderId().value());
        payload.put("merchantId", fixture.merchantId().value());
        payload.put("paymentIntentId", fixture.intentId());
        payload.put("capturedAmountMinor", CAPTURED);
        payload.put("currency", "INR");
        payload.put("orderStatus", "CANCELLED");
        payload.put("occurredAt", PROVIDER_EVENT.toString());

        return new OutboxEvent(
            EventId.generate(), fixture.merchantId(), "ORDER", fixture.order().orderId().value(),
            "order.payment_captured_after_close", 1, payload, PROVIDER_EVENT
        );
    }

    private static OutboxEvent withFreshId(OutboxEvent e) {
        return new OutboxEvent(
            EventId.generate(), e.merchantId(), e.aggregateType(), e.aggregateId(), e.eventType(),
            e.eventVersion(), e.payload(), e.occurredAt()
        );
    }
}
