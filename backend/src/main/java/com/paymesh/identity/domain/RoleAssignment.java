package com.paymesh.identity.domain;

/**
 * A role held by a user, scoped to one merchant (SDD 8.4 user_roles).
 *
 * <p>merchantId is a plain String, not merchant's MerchantId value object. Identity
 * and Merchant are separate modules on their way to separate services (ADR-001),
 * so identity holds the reference without importing the other module's domain or
 * re-implementing its format rules. It checks only what it can own: present, and
 * short enough for the column.
 */
public record RoleAssignment(Role role, String merchantId) {

    public RoleAssignment {
        if (role == null) {
            throw new IllegalArgumentException("Role cannot be null");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        merchantId = merchantId.trim();

        if (merchantId.isBlank()) {
            throw new IllegalArgumentException("Merchant Identifier cannot be blank");
        }

        if (merchantId.length() > 40) {
            throw new IllegalArgumentException(
                "Merchant Identifier cannot be longer than 40 characters"
            );
        }
    }
}
