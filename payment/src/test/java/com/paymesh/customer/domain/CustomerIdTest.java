package com.paymesh.customer.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerIdTest {

    @Test
    void generatesCustomerIdentifierWithExpectedPrefix() {
        CustomerId customerId = CustomerId.generate();

        assertTrue(customerId.value().startsWith("cus_"));
    }

    @Test
    void generatedIdentifierContainsValidUuidSuffix() {
        CustomerId customerId = CustomerId.generate();

        String uuidPart = customerId.value().substring("cus_".length());

        UUID.fromString(uuidPart);
    }

    @Test
    void generatesDifferentCustomerIdentifiers() {
        CustomerId firstCustomerId = CustomerId.generate();
        CustomerId secondCustomerId = CustomerId.generate();

        assertNotEquals(firstCustomerId, secondCustomerId);
    }

    @Test
    void parsesValidCustomerIdentifier() {
        String value = "cus_550e8400-e29b-41d4-a716-446655440000";

        CustomerId customerId = CustomerId.from(value);

        assertEquals(value, customerId.value());
    }

    @Test
    void preservesIdentifierValueWhenParsed() {
        String value = "cus_f47ac10b-58cc-4372-a567-0e02b2c3d479";

        CustomerId customerId = CustomerId.from(value);

        assertEquals(value, customerId.toString());
    }

    /**
     * A merchant id must not parse as a customer id. The prefix is what stops one capability's
     * identifier from being accepted where another's is expected.
     */
    @Test
    void rejectsIdentifierWithWrongResourcePrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CustomerId.from("mrc_550e8400-e29b-41d4-a716-446655440000")
        );
    }

    @Test
    void rejectsIdentifierWithoutPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CustomerId.from("550e8400-e29b-41d4-a716-446655440000")
        );
    }

    @Test
    void rejectsMalformedUuidSuffix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CustomerId.from("cus_not-a-uuid")
        );
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CustomerId.from("   ")
        );
    }

    @Test
    void rejectsNullIdentifier() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CustomerId.from(null)
        );
    }
}
