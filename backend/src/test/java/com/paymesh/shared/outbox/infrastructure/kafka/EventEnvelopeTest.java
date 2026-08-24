package com.paymesh.shared.outbox.infrastructure.kafka;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The topic-naming and partitioning rules of ADR-036, which are pure functions of the event and so
 * need no broker to assert.
 */
class EventEnvelopeTest {

    @Test
    void topicIsTheEventTypesDomainPrefixPlusEvents() {
        assertThat(envelopeOf("order.created", "ord_x").topic()).isEqualTo("order-events");
        assertThat(envelopeOf("payment.succeeded", "pi_x").topic()).isEqualTo("payment-events");
        assertThat(envelopeOf("refund.failed", "ref_x").topic()).isEqualTo("refund-events");
        assertThat(envelopeOf("settlement.batch_cut", "stl_x").topic()).isEqualTo("settlement-events");
    }

    /**
     * The one event type in the system with two dots. Only the FIRST segment names the topic, so a
     * sub-namespaced type stays with its domain instead of inventing a topic of its own.
     */
    @Test
    void aSubNamespacedEventTypeStaysOnItsDomainsTopic() {
        assertThat(envelopeOf("customer.payment_method.attached", "cus_x").topic())
            .isEqualTo("customer-events");
    }

    /**
     * A producer bug, refused at the boundary. Silently routing it to a topic named after the whole
     * type would create a topic nobody consumes and lose the event in plain sight.
     */
    @Test
    void rejectsAnEventTypeWithNoDomainPrefix() {
        assertThatThrownBy(() -> envelopeOf("succeeded", "pi_x").topic())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no domain prefix");
    }

    @Test
    void partitionKeyIsTheAggregateIdSoOneAggregatesEventsStayOrdered() {
        assertThat(envelopeOf("payment.succeeded", "pi_7").partitionKey()).isEqualTo("pi_7");
    }

    @Test
    void carriesEveryFactTheOutboxRowHolds() {
        OutboxEvent event = event("payment.succeeded", "pi_7");

        EventEnvelope envelope = EventEnvelope.from(event);

        assertThat(envelope.eventId()).isEqualTo(event.eventId().value());
        assertThat(envelope.merchantId()).isEqualTo(event.merchantId().value());
        assertThat(envelope.eventVersion()).isEqualTo(3);
        assertThat(envelope.toEvent()).isEqualTo(event);
    }

    private static EventEnvelope envelopeOf(String eventType, String aggregateId) {
        return EventEnvelope.from(event(eventType, aggregateId));
    }

    private static OutboxEvent event(String eventType, String aggregateId) {
        return new OutboxEvent(
            EventId.generate(),
            MerchantId.generate(),
            "PAYMENT_INTENT",
            aggregateId,
            eventType,
            3,
            Map.of("amountMinor", 1250),
            Instant.parse("2026-08-24T10:15:30Z")
        );
    }
}
