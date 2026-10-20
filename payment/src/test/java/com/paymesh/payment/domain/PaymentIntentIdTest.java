package com.paymesh.payment.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentIntentIdTest {

    @Test
    void generatesAPrefixedIdentifier() {
        PaymentIntentId id = PaymentIntentId.generate();

        assertTrue(id.value().startsWith("pi_"));
        assertEquals(UUID.fromString(id.value().substring(3)).toString(), id.value().substring(3));
    }

    @Test
    void mintsADistinctIdentifierEachTime() {
        assertTrue(!PaymentIntentId.generate().equals(PaymentIntentId.generate()));
    }

    @Test
    void parsesAWellFormedIdentifier() {
        String value = "pi_11111111-1111-4111-8111-111111111111";

        assertEquals(value, PaymentIntentId.from(value).value());
    }

    /**
     * The prefix is part of the identity, not decoration. Accepting a bare UUID or another
     * capability's prefix would let an order id be passed where an intent id belongs and resolve
     * against whichever table happened to hold that UUID.
     */
    @Test
    void rejectsAnotherCapabilitysPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentIntentId.from("ord_11111111-1111-4111-8111-111111111111")
        );
    }

    @Test
    void rejectsAnIdentifierWithNoPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentIntentId.from("11111111-1111-4111-8111-111111111111")
        );
    }

    @Test
    void rejectsAPrefixFollowedByRubbish() {
        assertThrows(IllegalArgumentException.class, () -> PaymentIntentId.from("pi_not-a-uuid"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertThrows(IllegalArgumentException.class, () -> PaymentIntentId.from(null));
        assertThrows(IllegalArgumentException.class, () -> PaymentIntentId.from("   "));
    }

    /**
     * "pay_" is reserved and must not resolve, so that a future payment record can own it without
     * colliding with identifiers already issued to merchants.
     */
    @Test
    void rejectsTheReservedPayPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentIntentId.from("pay_11111111-1111-4111-8111-111111111111")
        );
    }
}
