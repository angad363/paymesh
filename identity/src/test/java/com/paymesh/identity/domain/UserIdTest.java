package com.paymesh.identity.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserIdTest {

    @Test
    void generatesPrefixedIdentifier() {
        UserId userId = UserId.generate();

        assertTrue(userId.value().startsWith("usr_"));
        assertEquals(40, userId.value().length());
    }

    @Test
    void parsesAValidIdentifier() {
        String value = "usr_550e8400-e29b-41d4-a716-446655440000";

        assertEquals(value, UserId.from(value).value());
    }

    @Test
    void rejectsNullIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> UserId.from(null));
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> UserId.from("   "));
    }

    @Test
    void rejectsIdentifierWithTheWrongPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> UserId.from("mrc_550e8400-e29b-41d4-a716-446655440000")
        );
    }

    @Test
    void rejectsIdentifierWithAnInvalidUuid() {
        assertThrows(IllegalArgumentException.class, () -> UserId.from("usr_not-a-uuid"));
    }
}
