package com.paymesh.merchant.domain;

import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * A machine credential for one merchant. SDD 9.3 and 9.4.
 *
 * <h2>WHY THIS HAD TO EXIST</h2>
 *
 * SDD 10.3 and 11.3 say customers and orders are created with a "Merchant API key". There was no
 * such thing, so a merchant's backend had to authenticate as a human with a password -- the one
 * credential a server must never hold. Server-to-server integration was impossible as specified.
 *
 * <h2>THE SECRET IS NOT IN THIS OBJECT</h2>
 *
 * Only its hash is. The plaintext exists for the length of one HTTP response and is never stored,
 * logged or recoverable -- same rule as refresh tokens (V2). A credential a database reader can use
 * is not a credential.
 */
public record ApiCredential(
    ApiCredentialId apiCredentialId,
    MerchantId merchantId,
    String publicPrefix,
    String secretHash,
    CallerRole role,
    String label,
    Instant revokedAt,
    Instant lastUsedAt,
    Instant createdAt
) {

    public ApiCredential {
        if (apiCredentialId == null || merchantId == null) {
            throw new IllegalArgumentException("An API credential must identify itself and its merchant");
        }

        if (publicPrefix == null || publicPrefix.isBlank()) {
            throw new IllegalArgumentException("An API credential must have a public prefix");
        }

        if (secretHash == null || !secretHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("An API credential secret hash must be 64 hex characters");
        }

        if (role == null) {
            throw new IllegalArgumentException("An API credential must have a role");
        }

        // A PLATFORM_ADMIN key would let a string in a config file suspend merchants. No machine
        // needs that, and ck_api_credentials_role refuses it at the schema as well.
        if (role != CallerRole.MERCHANT_ADMIN && role != CallerRole.MERCHANT_USER) {
            throw new IllegalArgumentException(
                "An API credential may only be MERCHANT_ADMIN or MERCHANT_USER, got " + role
            );
        }

        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException(
                "An API credential must be labelled, so an operator can tell which to revoke"
            );
        }

        label = label.strip();

        if (createdAt == null) {
            throw new IllegalArgumentException("An API credential must have a creation instant");
        }
    }

    public static ApiCredential issue(
        MerchantId merchantId,
        String publicPrefix,
        String secretHash,
        CallerRole role,
        String label,
        Instant createdAt
    ) {
        return new ApiCredential(
            ApiCredentialId.generate(), merchantId, publicPrefix, secretHash, role, label,
            null, null, createdAt
        );
    }

    /** Revocation is a timestamp, never a delete: a deleted key cannot be reasoned about later. */
    public ApiCredential revoke(Instant revokedAt) {
        if (this.revokedAt != null) {
            throw new ApiCredentialAlreadyRevokedException(apiCredentialId);
        }

        return new ApiCredential(
            apiCredentialId, merchantId, publicPrefix, secretHash, role, label,
            revokedAt, lastUsedAt, createdAt
        );
    }

    public boolean isLive() {
        return revokedAt == null;
    }
}
