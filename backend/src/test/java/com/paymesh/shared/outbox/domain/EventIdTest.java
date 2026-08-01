package com.paymesh.shared.outbox.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventIdTest {

    @Test
    void generatesEventIdentifierWithExpectedPrefix() {
        assertTrue(EventId.generate().value().startsWith("evt_"));
    }

    @Test
    void generatedIdentifierContainsValidUuidSuffix() {
        UUID.fromString(EventId.generate().value().substring("evt_".length()));
    }

    @Test
    void generatesDifferentEventIdentifiers() {
        assertNotEquals(EventId.generate(), EventId.generate());
    }

    @Test
    void parsesValidEventIdentifier() {
        String value = "evt_550e8400-e29b-41d4-a716-446655440000";

        assertEquals(value, EventId.from(value).value());
    }

    /**
     * An order id must not parse as an event id. A consumer dedups on this value, so accepting a
     * foreign identifier here would let one capability's id silently become another's dedup key.
     */
    @Test
    void rejectsIdentifierWithWrongResourcePrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> EventId.from("ord_550e8400-e29b-41d4-a716-446655440000")
        );
    }

    @Test
    void rejectsMalformedUuidSuffix() {
        assertThrows(IllegalArgumentException.class, () -> EventId.from("evt_not-a-uuid"));
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> EventId.from("   "));
    }

    @Test
    void rejectsNullIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> EventId.from(null));
    }
}
