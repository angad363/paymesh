package com.paymesh.merchant.domain;

import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * The payment service's READ MODEL of a merchant API credential (ADR-048 section 4, option 3): just
 * what verification needs, fed from {@code merchant.api_credential.issued/revoked}. The merchant
 * service owns the full credential (label, creation time); this copy has neither, and
 * {@link #lastUsedAt()} is always null because this side never writes a last-used time.
 * <p>
 * The same type name and the same accessors the authenticator reads, so
 * {@code ApiCredentialAuthenticator} is the merchant service's class unchanged.
 */
public record ApiCredential(
    ApiCredentialId apiCredentialId,
    MerchantId merchantId,
    String publicPrefix,
    String secretHash,
    CallerRole role,
    Instant revokedAt,
    Instant lastUsedAt
) {

    public ApiCredential {
        if (apiCredentialId == null || merchantId == null || role == null) {
            throw new IllegalArgumentException("An API credential must have an id, a merchant and a role");
        }

        if (publicPrefix == null || publicPrefix.isBlank()) {
            throw new IllegalArgumentException("An API credential must have a public prefix");
        }

        if (secretHash == null || !secretHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("An API credential secret hash must be 64 hex characters");
        }
    }

    public boolean isLive() {
        return revokedAt == null;
    }
}
