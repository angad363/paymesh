package com.paymesh.order.infrastructure.events;

import com.paymesh.order.application.ApplyPaymentSucceededService;
import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unpacking half of Order's consumer: what it reads out of an untyped payload, and what it
 * refuses (ADR-016).
 * <p>
 * NOTE WHAT IS NOT IMPORTED HERE EITHER: anything from {@code com.paymesh.payment}. The test builds
 * the event by hand as a {@code Map}, which is the same thing a Kafka consumer would be handed and
 * the same thing {@code ModuleBoundaryTest.orderNeverImportsPayment} requires.
 */
class PaymentSucceededHandlerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-08-02T12:00:00Z");
    private static final Instant PROVIDER_SAID = Instant.parse("2026-08-02T11:59:00Z");

    private final SingleOrder orders = new SingleOrder();
    private final PaymentSucceededHandler handler = new PaymentSucceededHandler(
        new ApplyPaymentSucceededService(
            orders,
            change -> {
            },
            new GetOrderService(orders),
            event -> {
            }
        )
    );

    /**
     * THE INBOX KEY IS A LITERAL, NOT A CLASS NAME, and this test is what stops a rename becoming a
     * replay of every payment this consumer has ever seen. {@code consumer_name} is a primary key
     * column in {@code processed_events}; changing it re-opens the whole backlog.
     */
    @Test
    void namesItselfWithAStableConsumerNameAndOneEventType() {
        assertThat(handler.consumerName()).isEqualTo("order.payment-succeeded");
        assertThat(handler.eventType()).isEqualTo("payment.succeeded");
    }

    /**
     * THE NUMBER TRAP, AND IT IS REAL RATHER THAN DEFENSIVE STYLE. ADR-010 measured it: Hibernate
     * snapshots a JSONB attribute through serialize/deserialize, so a {@code Long} appended as
     * {@code capturedAmountMinor} comes back an {@code Integer}. A cast to {@code Long} would throw
     * {@code ClassCastException} on every amount that fits in 32 bits -- which is every amount this
     * platform will ever see, so the failure would be total rather than rare.
     * <p>
     * <b>Sabotage that must turn this red:</b> replace the {@code instanceof Number} read with
     * {@code (Long) payload.get("capturedAmountMinor")}.
     */
    @Test
    void readsTheCapturedAmountWhetherJsonGaveItBackAsAnIntegerOrALong() {
        Order order = orders.reset(4000);

        handler.handle(event(order, payload(order, 3000)));

        assertThat(orders.stored().status()).isEqualTo(OrderStatus.PARTIALLY_PAID);
        assertThat(orders.stored().amountPaidMinor()).isEqualTo(3000L);

        Order second = orders.reset(4000);
        Map<String, Object> asLong = payload(second, 3000);
        asLong.put("capturedAmountMinor", 3000L);

        handler.handle(event(second, asLong));

        assertThat(orders.stored().amountPaidMinor()).isEqualTo(3000L);
    }

    /**
     * THE PAYLOAD'S {@code occurredAt} WINS, BECAUSE IT IS THE AUTHORITY'S CLOCK. The envelope's is
     * when PayMesh recorded the fact, and a callback delivered late makes those two genuinely
     * different instants -- the order's timeline should say when the payment happened, not when the
     * platform got round to hearing about it.
     * <p>
     * Both emitters now carry the key. Before ADR-016 section 6 one called it {@code capturedAt} and
     * the other omitted it, at the same envelope version.
     */
    @Test
    void stampsTheOrderWithThePayloadsOccurredAtRatherThanTheEnvelopes() {
        Order order = orders.reset(1999);

        handler.handle(event(order, payload(order, 1999)));

        assertThat(orders.stored().updatedAt()).isEqualTo(PROVIDER_SAID);
    }

    /**
     * Falls back to the envelope rather than refusing. An order that cannot be marked paid because a
     * timestamp is missing is a worse outcome than one stamped a few seconds late, and the envelope's
     * instant is a true statement about the same fact.
     */
    @Test
    void fallsBackToTheEnvelopeWhenThePayloadCarriesNoTimestamp() {
        Order order = orders.reset(1999);
        Map<String, Object> withoutTimestamp = payload(order, 1999);
        withoutTimestamp.remove("occurredAt");

        handler.handle(event(order, withoutTimestamp));

        assertThat(orders.stored().updatedAt()).isEqualTo(RECORDED_AT);
    }

    /**
     * THE MERCHANT COMES FROM THE ENVELOPE, NOT THE PAYLOAD. The envelope's was copied from the
     * aggregate by the producer; a payload field is data. A payload claiming a different tenant must
     * not be able to steer this consumer at another merchant's order -- it finds nothing under the
     * envelope's merchant and throws, which the relay retries and logs.
     */
    @Test
    void takesTheMerchantFromTheEnvelopeAndIgnoresThePayloadsClaim() {
        Order order = orders.reset(1999);
        Map<String, Object> lying = payload(order, 1999);
        lying.put("merchantId", MerchantId.generate().value());

        handler.handle(event(order, lying));

        assertThat(orders.stored().status()).isEqualTo(OrderStatus.PAID);
    }

    /** A payload with no order to act on is a corrupt event, and saying so beats a NullPointerException. */
    @Test
    void refusesAnEventWithNoOrderId() {
        Order order = orders.reset(1999);
        Map<String, Object> missing = payload(order, 1999);
        missing.remove("orderId");

        assertThatThrownBy(() -> handler.handle(event(order, missing)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("orderId");
    }

    @Test
    void refusesAnEventWithNoCapturedAmount() {
        Order order = orders.reset(1999);
        Map<String, Object> missing = payload(order, 1999);
        missing.remove("capturedAmountMinor");

        assertThatThrownBy(() -> handler.handle(event(order, missing)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capturedAmountMinor");
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * The payload as the two Payment emitters now write it -- ONE shape from both, which is the fix
     * ADR-016 section 6 records. {@code capturedAmountMinor} is an {@code Integer} on purpose: that
     * is what comes back out of JSONB.
     */
    private static Map<String, Object> payload(Order order, int capturedAmountMinor) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", "pi_" + java.util.UUID.randomUUID());
        payload.put("merchantId", order.merchantId().value());
        payload.put("orderId", order.orderId().value());
        payload.put("customerId", null);
        payload.put("amountMinor", (int) order.amountMinor());
        payload.put("capturedAmountMinor", capturedAmountMinor);
        payload.put("currency", "INR");
        payload.put("captureMethod", "MANUAL");
        payload.put("previousStatus", "AUTHORIZED");
        payload.put("status", "SUCCEEDED");
        payload.put("occurredAt", PROVIDER_SAID.toString());
        return payload;
    }

    private static OutboxEvent event(Order order, Map<String, Object> payload) {
        return new OutboxEvent(
            EventId.generate(),
            order.merchantId(),
            "PAYMENT_INTENT",
            "pi_aggregate",
            "payment.succeeded",
            1,
            payload,
            RECORDED_AT
        );
    }

    /**
     * One order, scoped by merchant like the real port. Only the three reads the service uses are
     * implemented; the rest throw, so a future dependency on them cannot pass unnoticed.
     */
    private static final class SingleOrder implements OrderRepository {

        private final AtomicReference<Order> order = new AtomicReference<>();

        Order reset(long amountMinor) {
            Order created = Order.create(
                OrderId.generate(), MerchantId.generate(), null, null, amountMinor, "INR", null,
                Map.of(), null, CREATED_AT
            );
            order.set(created);
            return created;
        }

        Order stored() {
            return order.get();
        }

        @Override
        public Order save(Order saved) {
            order.set(saved);
            return saved;
        }

        @Override
        public Optional<Order> findByOrderId(MerchantId merchantId, OrderId orderId) {
            Order current = order.get();

            return current != null
                && current.merchantId().equals(merchantId)
                && current.orderId().equals(orderId)
                ? Optional.of(current)
                : Optional.empty();
        }

        @Override
        public Optional<Order> findByOrderIdForUpdate(MerchantId merchantId, OrderId orderId) {
            return findByOrderId(merchantId, orderId);
        }

        @Override
        public boolean existsByMerchantOrderReference(MerchantId merchantId, String reference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Order> findPage(
            MerchantId merchantId,
            OrderStatus status,
            com.paymesh.order.application.OrderCursor cursor,
            int limit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Order> findExpirable(Instant now, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
