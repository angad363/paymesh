package com.paymesh.merchant.api;

import com.paymesh.merchant.domain.ApiCredential;

import java.time.Instant;

/**
 * A credential WITHOUT its secret. Used for reads and for revoke.
 * <p>
 * There is deliberately no {@code secret} field on this type at all, rather than a nullable one:
 * a nullable secret field is a secret that leaks the first time somebody populates it in the wrong
 * branch. The one response that carries a secret uses a different type
 * ({@link CreatedApiCredentialResponse}), so leaking it requires choosing the wrong class rather
 * than forgetting a null check.
 */
public record ApiCredentialResponse(
    String id,
    String merchantId,
    String publicPrefix,
    String role,
    String label,
    Instant revokedAt,
    Instant lastUsedAt,
    Instant createdAt
) {

    public static ApiCredentialResponse from(ApiCredential credential) {
        return new ApiCredentialResponse(
            credential.apiCredentialId().value(),
            credential.merchantId().value(),
            credential.publicPrefix(),
            credential.role().name(),
            credential.label(),
            credential.revokedAt(),
            credential.lastUsedAt(),
            credential.createdAt()
        );
    }
}
