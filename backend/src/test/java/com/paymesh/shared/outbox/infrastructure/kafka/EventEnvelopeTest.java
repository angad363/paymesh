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
    void topicIsTheAggregateTypeHyphenatedPlusEvents() {
        assertThat(envelopeOf("ORDER", "order.created", "ord_x").topic()).isEqualTo("order-events");
        assertThat(envelopeOf("CUSTOMER", "customer.payment_method.attached", "cus_x").topic())
            .isEqualTo("customer-events");
        assertThat(envelopeOf("PAYMENT_INTENT", "payment.succeeded", "pi_x").topic())
            .isEqualTo("payment-intent-events");
    }

    /**
     * THE REASON THE RULE READS THE AGGREGATE AND NOT THE EVENT TYPE'S PREFIX.
     * <p>
     * One {@code SETTLEMENT_BATCH} emits {@code settlement.batch_cut} and then
     * {@code payout.paid}, and the Ledger consumes both: the first moves available to in-transit,
     * the second discharges in-transit. A rule keyed on the event type's prefix would put them on
     * {@code settlement-events} and {@code payout-events}, where the shared {@code stl_} key orders
     * nothing, and the Ledger could be asked to discharge funds it had not yet moved.
     */
    @Test
    void oneAggregatesEventsShareATopicEvenWhenTheirTypesDoNot() {
        String cut = envelopeOf("SETTLEMENT_BATCH", "settlement.batch_cut", "stl_1").topic();
        String paid = envelopeOf("SETTLEMENT_BATCH", "payout.paid", "stl_1").topic();
        String returned = envelopeOf("SETTLEMENT_BATCH", "payout.returned", "stl_1").topic();

        assertThat(cut).isEqualTo("settlement-batch-events");
        assertThat(paid).isEqualTo(cut);
        assertThat(returned).isEqualTo(cut);
    }

    /**
     * {@code aggregateType} is free text to the outbox, so the boundary has to refuse what cannot
     * be a topic rather than letting the broker reject it later, per event, forever.
     */
    @Test
    void rejectsAnAggregateTypeThatCannotFormATopicName() {
        assertThatThrownBy(() -> envelopeOf("SETTLEMENT BATCH", "settlement.batch_cut", "stl_1").topic())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("legal topic name");
    }

    @Test
    void partitionKeyIsTheAggregateIdSoOneAggregatesEventsStayOrdered() {
        assertThat(envelopeOf("PAYMENT_INTENT", "payment.succeeded", "pi_7").partitionKey())
            .isEqualTo("pi_7");
    }

    @Test
    void carriesEveryFactTheOutboxRowHolds() {
        OutboxEvent event = event("PAYMENT_INTENT", "payment.succeeded", "pi_7");

        EventEnvelope envelope = EventEnvelope.from(event);

        assertThat(envelope.eventId()).isEqualTo(event.eventId().value());
        assertThat(envelope.merchantId()).isEqualTo(event.merchantId().value());
        assertThat(envelope.eventVersion()).isEqualTo(3);
        assertThat(envelope.toEvent()).isEqualTo(event);
    }

    private static EventEnvelope envelopeOf(
        String aggregateType, String eventType, String aggregateId
    ) {
        return EventEnvelope.from(event(aggregateType, eventType, aggregateId));
    }

    private static OutboxEvent event(String aggregateType, String eventType, String aggregateId) {
        return new OutboxEvent(
            EventId.generate(),
            MerchantId.generate(),
            aggregateType,
            aggregateId,
            eventType,
            3,
            Map.of("amountMinor", 1250),
            Instant.parse("2026-08-24T10:15:30Z")
        );
    }
}
