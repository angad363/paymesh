package com.paymesh.identity.api;

import com.paymesh.identity.domain.RoleAssignment;
import com.paymesh.identity.domain.User;

import java.time.Instant;
import java.util.List;

/**
 * A user as an administrator sees them.
 * <p>
 * NO PASSWORD HASH and no email hash. The hash is not "less sensitive plaintext" -- it is the thing
 * an offline attack runs against, and an admin listing their staff has no use for it.
 */
public record UserAdminResponse(
    String id,
    String email,
    String status,
    List<ScopedRole> roles,
    Instant createdAt,
    Instant updatedAt
) {

    public record ScopedRole(String role, String merchantId) {
    }

    public static UserAdminResponse from(User user) {
        return new UserAdminResponse(
            user.userId().value(),
            user.email(),
            user.status().name(),
            user.roles().stream()
                .map(UserAdminResponse::toScopedRole)
                .toList(),
            user.createdAt(),
            user.updatedAt()
        );
    }

    private static ScopedRole toScopedRole(RoleAssignment assignment) {
        return new ScopedRole(assignment.role().name(), assignment.merchantId());
    }
}
