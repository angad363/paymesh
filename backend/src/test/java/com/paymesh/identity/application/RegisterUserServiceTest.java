package com.paymesh.identity.application;

import com.paymesh.identity.domain.SecurityEventType;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserStatus;
import com.paymesh.identity.infrastructure.security.BCryptPasswordHasher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterUserServiceTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-07-18T10:15:30Z");
    private static final String PASSWORD = "correct-horse-battery-staple";

    private final Fakes.UserRepositoryFake users = new Fakes.UserRepositoryFake();
    private final Fakes.SecurityEventRepositoryFake securityEvents =
        new Fakes.SecurityEventRepositoryFake();

    // Strength 4 is BCrypt's minimum: real algorithm, no deliberate slowdown.
    private final PasswordHasher passwordHasher = new BCryptPasswordHasher(4);

    private final RegisterUserService service = new RegisterUserService(
        users,
        securityEvents,
        passwordHasher,
        Clock.fixed(REGISTERED_AT, ZoneOffset.UTC)
    );

    @Test
    void registersActiveUserWithNormalizedEmail() {
        User user = service.register(command(" Owner@FreshBrew.EXAMPLE ", PASSWORD));

        assertEquals("owner@freshbrew.example", user.email());
        assertEquals(UserStatus.ACTIVE, user.status());
        assertEquals(REGISTERED_AT, user.createdAt());
        assertEquals(List.of(user), users.saved());
    }

    @Test
    void neverStoresThePasswordItself() {
        User user = service.register(command("owner@freshbrew.example", PASSWORD));

        assertNotEquals(PASSWORD, user.passwordHash());
        assertTrue(user.passwordHash().startsWith("$2"));
        assertTrue(passwordHasher.matches(PASSWORD, user.passwordHash()));
    }

    @Test
    void grantsNoRoles() {
        User user = service.register(command("owner@freshbrew.example", PASSWORD));

        assertEquals(List.of(), user.roles());
    }

    @Test
    void rejectsRegistrationWhenEmailAlreadyExists() {
        service.register(command("Owner@FreshBrew.Example", PASSWORD));

        assertThrows(
            UserEmailAlreadyExistsException.class,
            () -> service.register(command(" owner@freshbrew.example ", PASSWORD))
        );

        assertEquals(1, users.saved().size());
    }

    @Test
    void rejectsPasswordShorterThanTheMinimum() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.register(command("owner@freshbrew.example", "short"))
        );
    }

    /** BCrypt ignores everything past 72 bytes, so a longer password must be refused. */
    @Test
    void rejectsPasswordLongerThanBCryptCanHash() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.register(command("owner@freshbrew.example", "a".repeat(73)))
        );
    }

    @Test
    void rejectsNullCommand() {
        assertThrows(IllegalArgumentException.class, () -> service.register(null));
    }

    @Test
    void recordsARegistrationSecurityEvent() {
        User user = service.register(command("owner@freshbrew.example", PASSWORD));

        assertEquals(List.of(SecurityEventType.USER_REGISTERED), securityEvents.types());
        assertEquals(user.userId().value(), securityEvents.events().getFirst().actor());
        assertEquals(REGISTERED_AT, securityEvents.events().getFirst().occurredAt());
    }

    /** The audit trail keeps a digest of the caller's address, never the address. */
    @Test
    void hashesTheCallerAddressInTheAuditTrail() {
        service.register(
            new RegisterUserCommand("owner@freshbrew.example", PASSWORD, "203.0.113.7")
        );

        String ipHash = securityEvents.events().getFirst().ipHash();

        assertEquals(64, ipHash.length());
        assertNotEquals("203.0.113.7", ipHash);
    }

    private static RegisterUserCommand command(String email, String password) {
        return new RegisterUserCommand(email, password, "203.0.113.7");
    }
}
