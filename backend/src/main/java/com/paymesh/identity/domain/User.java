package com.paymesh.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * A human identity that can authenticate against PayMesh.
 *
 * <p>The aggregate never sees a plaintext password. Hashing needs BCrypt, which is
 * a Spring Security class, and the domain stays framework-free -- so the caller
 * hashes first and hands the result in. What the domain does own is that the field
 * it stores is a hash and not a password: {@link #register} rejects anything that
 * is not a BCrypt encoding.
 */
public final class User {
    private final UserId userId;
    private final String email;
    private final String passwordHash;
    private final UserStatus status;
    private final List<RoleAssignment> roles;
    private final Instant createdAt;
    private final Instant updatedAt;

    private User(
        UserId userId,
        String email,
        String passwordHash,
        UserStatus status,
        List<RoleAssignment> roles,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.roles = roles;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User register(
        UserId userId,
        String email,
        String passwordHash,
        List<RoleAssignment> roles,
        Instant registeredAt
    ) {
        UserId validatedUserId = requireUserId(userId);
        String normalizedEmail = normalizeEmail(email);
        String validatedPasswordHash = requirePasswordHash(passwordHash);
        List<RoleAssignment> validatedRoles = normalizeRoles(roles);
        Instant validatedRegisteredAt = requireRegistrationTimestamp(registeredAt);

        return new User(
            validatedUserId,
            normalizedEmail,
            validatedPasswordHash,
            UserStatus.ACTIVE,
            validatedRoles,
            validatedRegisteredAt,
            validatedRegisteredAt
        );
    }

    /**
     * Rebuilds a User from already-persisted state (ADR-004). Deliberately does NOT
     * re-normalize: those values passed through register() before they were stored,
     * so re-normalizing on read would mask corruption. Unlike register(), it can
     * restore any status and a distinct updatedAt.
     */
    public static User reconstitute(
        UserId userId,
        String email,
        String passwordHash,
        UserStatus status,
        List<RoleAssignment> roles,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new User(
            userId,
            email,
            passwordHash,
            status,
            List.copyOf(roles),
            createdAt,
            updatedAt
        );
    }

    /**
     * Whether this identity is allowed to open a session. Checked only after the
     * password has been verified, so a caller who fails here has proved who they
     * are -- which is what makes 403 (not 401) the right answer at the HTTP edge.
     */
    public boolean canAuthenticate() {
        return status == UserStatus.ACTIVE;
    }

    private static UserId requireUserId(UserId userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User Identifier cannot be null");
        }

        return userId;
    }

    /**
     * Public because login has to look an account up by the same normalized form
     * that registration stored. Keeping the rule here rather than duplicating
     * {@code trim().toLowerCase()} in the application layer is what guarantees
     * " Owner@Example.COM " and "owner@example.com" resolve to the same row.
     */
    public static String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }

        String normalizedEmail = email.trim();

        if (normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("Email cannot be blank");
        }

        if (normalizedEmail.length() > 320) {
            throw new IllegalArgumentException("Email cannot be longer than 320 characters");
        }

        return normalizedEmail.toLowerCase(Locale.ROOT);
    }

    /**
     * The one invariant that protects the whole login path: a plaintext password
     * must never reach the password_hash column. BCrypt encodings always start
     * with $2 and are 60 characters, so a caller who forgot to hash fails here
     * instead of silently storing the secret.
     */
    private static String requirePasswordHash(String passwordHash) {
        if (passwordHash == null) {
            throw new IllegalArgumentException("Password hash cannot be null");
        }

        if (!passwordHash.startsWith("$2") || passwordHash.length() != 60) {
            throw new IllegalArgumentException("Password hash must be a BCrypt hash");
        }

        return passwordHash;
    }

    private static List<RoleAssignment> normalizeRoles(List<RoleAssignment> roles) {
        if (roles == null) {
            throw new IllegalArgumentException("Roles cannot be null");
        }

        // Distinct because (user_id, merchant_id, role) is the primary key of
        // user_roles: a duplicate in this list would become a constraint violation
        // on save rather than a readable error here.
        return roles.stream().distinct().toList();
    }

    private static Instant requireRegistrationTimestamp(Instant registeredAt) {
        if (registeredAt == null) {
            throw new IllegalArgumentException("Registration timestamp cannot be null");
        }

        return registeredAt;
    }

    public UserId userId() {
        return userId;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    /**
     * Disable a user without deleting them.
     *
     * <h2>{@code isActive()} WAS CHECKED AT LOGIN AND COULD NEVER BE FALSE</h2>
     *
     * {@code UserStatus} has had three values since V2 and only ACTIVE was ever produced, so the
     * login gate was dead code and there was no way to disable a departed employee's account. One
     * of the three frozen lifecycle enums the Phase 1 audit found. ADR-021.
     *
     * <h2>What it does and does not stop</h2>
     *
     * It stops LOGIN and it stops REFRESH -- both read the user. It does <b>not</b> invalidate an
     * access token already issued, which survives its remaining lifetime because nothing checks a
     * denylist (open item 11, unchanged by this). Suspension therefore takes effect within the
     * access-token lifetime rather than instantly, and revoking the refresh-token family is what
     * makes it stick.
     */
    public User suspend(Instant suspendedAt) {
        requireStatusChange(UserStatus.SUSPENDED, suspendedAt);

        return withStatus(UserStatus.SUSPENDED, suspendedAt);
    }

    /** Reversible: a suspension is an investigation, not a verdict. */
    public User reactivate(Instant reactivatedAt) {
        requireStatusChange(UserStatus.ACTIVE, reactivatedAt);

        return withStatus(UserStatus.ACTIVE, reactivatedAt);
    }

    /** Terminal, like a merchant closure and for the same reason. */
    public User close(Instant closedAt) {
        if (status == UserStatus.CLOSED) {
            throw new UserStatusNotChangeableException(userId, status, UserStatus.CLOSED);
        }

        return withStatus(UserStatus.CLOSED, closedAt);
    }

    private User withStatus(UserStatus next, Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("A user status change needs an instant");
        }

        return new User(userId, email, passwordHash, next, roles, createdAt, at);
    }

    private void requireStatusChange(UserStatus next, Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("A user status change needs an instant");
        }

        // CLOSED is terminal; anything else may move to anything else.
        if (status == UserStatus.CLOSED || status == next) {
            throw new UserStatusNotChangeableException(userId, status, next);
        }
    }

    public UserStatus status() {
        return status;
    }

    public List<RoleAssignment> roles() {
        return roles;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
