package com.paymesh.shared.security;

import java.util.Locale;
import java.util.Optional;

/**
 * The roles an access token may carry, as the platform reads them off the claim.
 *
 * <h2>WHY THIS IS NOT {@code com.paymesh.identity.domain.Role}</h2>
 *
 * It has the same four constants and it is deliberately a separate type. {@code shared} may not
 * import a capability -- {@code ModuleBoundaryTest} enforces it -- and Identity is a capability.
 * The token claim is a PUBLISHED contract between the module that mints tokens and the platform
 * that reads them, exactly as the simulator publishes rather than shares the callback contract
 * (ADR-017).
 * <p>
 * The cost of publishing rather than sharing is that the two can drift, so
 * {@code AuthorizationBoundaryTest} asserts the two enums hold the same constants and fails the
 * build when they do not. That is the notification a shared type would have suppressed.
 */
public enum CallerRole {

    /**
     * Platform staff. The only role that may change a merchant's status or decide KYC.
     *
     * <p>Held platform-wide, never at a merchant: it arrives in the claim with no {@code :merchant}
     * suffix, and the suffixed form is discarded rather than honoured (ADR-027).
     */
    PLATFORM_ADMIN,

    /** Full authority over one merchant, including refunds and API credentials. */
    MERCHANT_ADMIN,

    /**
     * Day-to-day operation of one merchant: create and read orders, customers and intents, and
     * capture. <b>Not</b> refunds, not credentials, not the merchant profile -- the three things
     * that move money outward or widen access.
     */
    MERCHANT_USER,

    /** A machine identity. Reserved; nothing mints one yet. */
    SERVICE_ACCOUNT;

    /**
     * True when this role is held across the platform rather than at one merchant.
     *
     * <p>Mirrors {@code identity.domain.Role.isPlatformScoped()} for the same reason the enum
     * itself is duplicated: {@code shared} may not import a capability, so the token contract is
     * published rather than shared. {@code AuthorizationBoundaryTest} asserts the two agree.
     */
    public boolean isPlatformScoped() {
        return this == PLATFORM_ADMIN;
    }

    /** Empty for anything unrecognised -- an unknown role grants nothing rather than everything. */
    public static Optional<CallerRole> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** May a caller holding this role move money out or widen access? */
    public boolean isMerchantAdministrator() {
        return this == MERCHANT_ADMIN;
    }
}
