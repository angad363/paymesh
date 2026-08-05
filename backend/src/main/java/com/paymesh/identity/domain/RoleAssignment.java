package com.paymesh.identity.domain;

/**
 * A role held by a user, either at one merchant or platform-wide (SDD 8.4 user_roles).
 *
 * <p>merchantId is a plain String, not merchant's MerchantId value object. Identity
 * and Merchant are separate modules on their way to separate services (ADR-001),
 * so identity holds the reference without importing the other module's domain or
 * re-implementing its format rules. It checks only what it can own: present, and
 * short enough for the column.
 *
 * <h2>A NULL MERCHANT MEANS PLATFORM-WIDE, AND ONLY A PLATFORM ROLE MAY HAVE ONE</h2>
 *
 * Before V23 this record required a merchant, because the column did. Now that the column is
 * nullable, "null" has to mean exactly one thing or it will drift into meaning "unknown" or "any"
 * -- and "any merchant" is precisely the reading that let a merchant's own admin be treated as
 * platform staff. So the pairing is checked in both directions here and again in
 * {@code ck_user_roles_scope}: a platform role must NOT name a merchant, and every other role
 * must. ADR-027.
 */
public record RoleAssignment(Role role, String merchantId) {

    public RoleAssignment {
        if (role == null) {
            throw new IllegalArgumentException("Role cannot be null");
        }

        if (role.isPlatformScoped()) {
            if (merchantId != null) {
                throw new IllegalArgumentException(
                    role + " is platform-wide and cannot be scoped to a merchant"
                );
            }
        } else {
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

    /** A platform-wide grant of {@code role}, with no merchant scope. */
    public static RoleAssignment platformWide(Role role) {
        return new RoleAssignment(role, null);
    }

    /** True when this grant reaches across every tenant rather than naming one. */
    public boolean isPlatformScoped() {
        return merchantId == null;
    }
}
