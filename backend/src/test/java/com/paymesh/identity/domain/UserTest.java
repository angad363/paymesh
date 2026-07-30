package com.paymesh.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTest {

    private static final String BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final Instant REGISTERED_AT = Instant.parse("2026-07-18T10:15:30Z");

    @Test
    void registersUserAsActiveWithNormalizedEmail() {
        User user = User.register(
            UserId.generate(),
            "  Owner@FreshBrew.EXAMPLE  ",
            BCRYPT_HASH,
            List.of(),
            REGISTERED_AT
        );

        assertEquals("owner@freshbrew.example", user.email());
        assertEquals(UserStatus.ACTIVE, user.status());
        assertEquals(REGISTERED_AT, user.createdAt());
        assertEquals(REGISTERED_AT, user.updatedAt());
        assertTrue(user.canAuthenticate());
    }

    @Test
    void keepsScopedRoleAssignments() {
        String merchantId = "mrc_550e8400-e29b-41d4-a716-446655440000";

        User user = register(List.of(new RoleAssignment(Role.MERCHANT_ADMIN, merchantId)));

        assertEquals(
            List.of(new RoleAssignment(Role.MERCHANT_ADMIN, merchantId)),
            user.roles()
        );
    }

    /** A duplicate would violate the (user_id, merchant_id, role) primary key on save. */
    @Test
    void collapsesDuplicateRoleAssignments() {
        String merchantId = "mrc_550e8400-e29b-41d4-a716-446655440000";

        User user = register(List.of(
            new RoleAssignment(Role.MERCHANT_ADMIN, merchantId),
            new RoleAssignment(Role.MERCHANT_ADMIN, merchantId)
        ));

        assertEquals(1, user.roles().size());
    }

    /** The invariant that stops a forgotten hashing step from storing a password. */
    @Test
    void rejectsAPasswordThatWasNeverHashed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> User.register(
                UserId.generate(),
                "owner@freshbrew.example",
                "correct horse battery staple",
                List.of(),
                REGISTERED_AT
            )
        );
    }

    @Test
    void rejectsNullPasswordHash() {
        assertThrows(
            IllegalArgumentException.class,
            () -> User.register(
                UserId.generate(),
                "owner@freshbrew.example",
                null,
                List.of(),
                REGISTERED_AT
            )
        );
    }

    @Test
    void rejectsBlankEmail() {
        assertThrows(
            IllegalArgumentException.class,
            () -> User.register(UserId.generate(), "   ", BCRYPT_HASH, List.of(), REGISTERED_AT)
        );
    }

    @Test
    void rejectsEmailLongerThanTheColumn() {
        String tooLong = "a".repeat(310) + "@example.com";

        assertThrows(
            IllegalArgumentException.class,
            () -> User.register(UserId.generate(), tooLong, BCRYPT_HASH, List.of(), REGISTERED_AT)
        );
    }

    @Test
    void rejectsNullUserId() {
        assertThrows(
            IllegalArgumentException.class,
            () -> User.register(null, "owner@freshbrew.example", BCRYPT_HASH, List.of(), REGISTERED_AT)
        );
    }

    @Test
    void normalizesEmailTheSameWayForLookupAsForRegistration() {
        User user = register(List.of());

        assertEquals(user.email(), User.normalizeEmail("  OWNER@FreshBrew.example "));
    }

    @Test
    void suspendedUserCannotAuthenticate() {
        User user = User.reconstitute(
            UserId.generate(),
            "owner@freshbrew.example",
            BCRYPT_HASH,
            UserStatus.SUSPENDED,
            List.of(),
            REGISTERED_AT,
            REGISTERED_AT
        );

        assertFalse(user.canAuthenticate());
    }

    /** reconstitute restores stored state verbatim -- no re-normalization (ADR-004). */
    @Test
    void reconstituteRestoresStateWithoutRenormalizing() {
        Instant updatedAt = REGISTERED_AT.plusSeconds(3600);

        User user = User.reconstitute(
            UserId.generate(),
            "Already@Stored.Example",
            BCRYPT_HASH,
            UserStatus.CLOSED,
            List.of(),
            REGISTERED_AT,
            updatedAt
        );

        assertEquals("Already@Stored.Example", user.email());
        assertEquals(UserStatus.CLOSED, user.status());
        assertEquals(updatedAt, user.updatedAt());
    }

    private static User register(List<RoleAssignment> roles) {
        return User.register(
            UserId.generate(),
            "owner@freshbrew.example",
            BCRYPT_HASH,
            roles,
            REGISTERED_AT
        );
    }
}
