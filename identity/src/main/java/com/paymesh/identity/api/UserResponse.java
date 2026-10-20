package com.paymesh.identity.api;

import com.paymesh.identity.domain.RoleAssignment;
import com.paymesh.identity.domain.User;

import java.time.Instant;
import java.util.List;

/**
 * Public view of a user. Note what is absent: passwordHash never leaves the
 * persistence layer (REST conventions section 37).
 *
 * <p>The {@code id} field name follows the merchant API, which returns {@code id}
 * rather than the {@code merchantId} the conventions document specifies. Matching
 * the existing code beats matching the doc.
 */
public record UserResponse(
    String id,
    String email,
    String status,
    List<RoleResponse> roles,
    Instant createdAt,
    Instant updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.userId().value(),
            user.email(),
            user.status().name(),
            user.roles().stream().map(RoleResponse::from).toList(),
            user.createdAt(),
            user.updatedAt()
        );
    }

    public record RoleResponse(String role, String merchantId) {
        static RoleResponse from(RoleAssignment assignment) {
            return new RoleResponse(assignment.role().name(), assignment.merchantId());
        }
    }
}
