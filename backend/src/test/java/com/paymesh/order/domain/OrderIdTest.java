package com.paymesh.order.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderIdTest {

    @Test
    void generatesOrderIdentifierWithExpectedPrefix() {
        assertTrue(OrderId.generate().value().startsWith("ord_"));
    }

    @Test
    void generatedIdentifierContainsValidUuidSuffix() {
        UUID.fromString(OrderId.generate().value().substring("ord_".length()));
    }

    @Test
    void generatesDifferentOrderIdentifiers() {
        assertNotEquals(OrderId.generate(), OrderId.generate());
    }

    @Test
    void parsesValidOrderIdentifier() {
        String value = "ord_550e8400-e29b-41d4-a716-446655440000";

        assertEquals(value, OrderId.from(value).value());
    }

    @Test
    void preservesIdentifierValueWhenParsed() {
        String value = "ord_f47ac10b-58cc-4372-a567-0e02b2c3d479";

        assertEquals(value, OrderId.from(value).toString());
    }

    /**
     * A customer id must not parse as an order id. The prefix is what stops one capability's
     * identifier from being accepted where another's is expected.
     */
    @Test
    void rejectsIdentifierWithWrongResourcePrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OrderId.from("cus_550e8400-e29b-41d4-a716-446655440000")
        );
    }

    @Test
    void rejectsIdentifierWithoutPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OrderId.from("550e8400-e29b-41d4-a716-446655440000")
        );
    }

    @Test
    void rejectsMalformedUuidSuffix() {
        assertThrows(IllegalArgumentException.class, () -> OrderId.from("ord_not-a-uuid"));
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> OrderId.from("   "));
    }

    @Test
    void rejectsNullIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> OrderId.from(null));
    }
}
