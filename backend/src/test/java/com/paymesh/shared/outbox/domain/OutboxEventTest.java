package com.paymesh.shared.outbox.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T10:15:30Z");
    private static final MerchantId MERCHANT_ID = MerchantId.generate();

    @Test
    void mintsItsOwnIdentifier() {
        assertTrue(event(Map.of("orderId", "ord_1")).eventId().value().startsWith("evt_"));
    }

    @Test
    void carriesTheEnvelopeItWasGiven() {
        OutboxEvent event = event(Map.of("orderId", "ord_1"));

        assertEquals(MERCHANT_ID, event.merchantId());
        assertEquals("ORDER", event.aggregateType());
        assertEquals("ord_1", event.aggregateId());
        assertEquals("order.created", event.eventType());
        assertEquals(1, event.eventVersion());
        assertEquals(OCCURRED_AT, event.occurredAt());
    }

    /**
     * The payload is handed to a persistence adapter and then, one day, to a relay. A caller that
     * keeps mutating its map after appending must not be able to change what was recorded.
     */
    @Test
    void copiesThePayloadSoALaterMutationCannotChangeIt() {
        Map<String, Object> payload = new HashMap<>(Map.of("orderId", "ord_1"));
        OutboxEvent event = event(payload);

        payload.put("amountMinor", 1999L);

        assertEquals(Map.of("orderId", "ord_1"), event.payload());
    }

    /**
     * An absent optional field (a guest checkout has no customer) is carried as an explicit JSON
     * null, so every event of a type has the same shape. Map.of and Map.copyOf both refuse a null
     * value, which is why the copy below is not one of them.
     */
    @Test
    void carriesAnAbsentPayloadValueAsAnExplicitNull() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("customerId", null);

        assertTrue(event(payload).payload().containsKey("customerId"));
    }

    @Test
    void rejectsANullMerchant() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxEvent(
            EventId.generate(), null, "ORDER", "ord_1", "order.created", 1, Map.of(), OCCURRED_AT
        ));
    }

    @Test
    void rejectsABlankAggregateType() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxEvent(
            EventId.generate(), MERCHANT_ID, "  ", "ord_1", "order.created", 1, Map.of(), OCCURRED_AT
        ));
    }

    @Test
    void rejectsABlankAggregateId() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxEvent(
            EventId.generate(), MERCHANT_ID, "ORDER", null, "order.created", 1, Map.of(), OCCURRED_AT
        ));
    }

    @Test
    void rejectsABlankEventType() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxEvent(
            EventId.generate(), MERCHANT_ID, "ORDER", "ord_1", " ", 1, Map.of(), OCCURRED_AT
        ));
    }

    /** Mirrors ck_outbox_events_version: a version of zero would make the envelope unversioned. */
    @Test
    void rejectsANonPositiveEventVersion() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxEvent(
            EventId.generate(), MERCHANT_ID, "ORDER", "ord_1", "order.created", 0, Map.of(), OCCURRED_AT
        ));
    }

    @Test
    void rejectsAMissingOccurredAt() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxEvent(
            EventId.generate(), MERCHANT_ID, "ORDER", "ord_1", "order.created", 1, Map.of(), null
        ));
    }

    private static OutboxEvent event(Map<String, Object> payload) {
        return new OutboxEvent(
            EventId.generate(),
            MERCHANT_ID,
            "ORDER",
            "ord_1",
            "order.created",
            1,
            payload,
            OCCURRED_AT
        );
    }
}
