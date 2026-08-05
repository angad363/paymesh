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

    /** A duplicate would violate uq_user_roles_merchant_scoped on save (V23). */
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

    // --- platform scope (ADR-027) ------------------------------------------------------------

    /**
     * THE ESCALATION THIS WHOLE CHANGE EXISTS TO PREVENT.
     *
     * <p>{@code grantRoleAt} is the merchant-scoped path, reachable by any MERCHANT_ADMIN over
     * their own tenant. If it accepted PLATFORM_ADMIN, a merchant's own admin could promote
     * themselves to authority over every other merchant on the platform -- including the power to
     * lift their own suspension. Refused here, in {@code RoleAssignment}, and again by
     * {@code ck_user_roles_scope}.
     */
    @Test
    void refusesToGrantAPlatformRoleAtAMerchant() {
        User user = register(List.of());

        assertThrows(IllegalArgumentException.class, () ->
            user.grantRoleAt(Role.PLATFORM_ADMIN, "mrc_" + java.util.UUID.randomUUID(), REGISTERED_AT));
    }

    /** And the mirror: a merchant role cannot be held platform-wide either. */
    @Test
    void refusesToGrantAMerchantRolePlatformWide() {
        User user = register(List.of());

        assertThrows(IllegalArgumentException.class, () ->
            user.grantPlatformRole(Role.MERCHANT_ADMIN, REGISTERED_AT));
    }

    @Test
    void grantsAPlatformRoleWithNoMerchantScope() {
        User promoted = register(List.of()).grantPlatformRole(Role.PLATFORM_ADMIN, REGISTERED_AT);

        assertTrue(promoted.hasPlatformRole(Role.PLATFORM_ADMIN));
        assertEquals(1, promoted.roles().size());
        assertTrue(promoted.roles().getFirst().isPlatformScoped());
    }

    @Test
    void refusesToGrantAPlatformRoleTwice() {
        User promoted = register(List.of()).grantPlatformRole(Role.PLATFORM_ADMIN, REGISTERED_AT);

        assertThrows(UserAlreadyHoldsRoleException.class, () ->
            promoted.grantPlatformRole(Role.PLATFORM_ADMIN, REGISTERED_AT));
    }

    @Test
    void revokingAPlatformRoleLeavesTheAccount() {
        User demoted = register(List.of())
            .grantPlatformRole(Role.PLATFORM_ADMIN, REGISTERED_AT)
            .revokePlatformRole(Role.PLATFORM_ADMIN, REGISTERED_AT);

        assertFalse(demoted.hasPlatformRole(Role.PLATFORM_ADMIN));
        assertEquals(UserStatus.ACTIVE, demoted.status());
    }

    @Test
    void refusesToRevokeAPlatformRoleNobodyHolds() {
        User user = register(List.of());

        assertThrows(UserHoldsNoPlatformRoleException.class, () ->
            user.revokePlatformRole(Role.PLATFORM_ADMIN, REGISTERED_AT));
    }

    /**
     * A PLATFORM GRANT SURVIVES A MERCHANT REVOCATION, and it must -- the two scopes are
     * deliberately independent (ADR-024), so a merchant admin removing a departed employee must not
     * be able to strip that person's platform authority as a side effect.
     *
     * <p>It also pins the null-safety: {@code revokeRolesAt} filtered with
     * {@code assignment.merchantId().equals(...)}, which throws on a platform grant's null
     * merchant. The arguments are now the other way round.
     */
    @Test
    void revokingAtAMerchantLeavesAPlatformGrantAlone() {
        String merchantId = "mrc_" + java.util.UUID.randomUUID();

        User user = register(List.of(new RoleAssignment(Role.MERCHANT_ADMIN, merchantId)))
            .grantPlatformRole(Role.PLATFORM_ADMIN, REGISTERED_AT)
            .revokeRolesAt(merchantId, REGISTERED_AT);

        assertTrue(user.hasPlatformRole(Role.PLATFORM_ADMIN));
        assertFalse(user.hasRoleAt(merchantId));
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
