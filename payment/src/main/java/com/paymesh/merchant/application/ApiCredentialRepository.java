package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.merchant.domain.ApiCredentialId;

import java.time.Instant;
import java.util.Optional;

/**
 * The READ side of the merchant service's repository of the same name, over the {@code
 * api_credential_ref} projection (ADR-048 section 4). Issuing, revoking and listing credentials
 * stay with the merchant service; this service only verifies.
 */
public interface ApiCredentialRepository {

    /** By the public half, unique platform-wide. NOT merchant-scoped: authentication establishes the merchant. */
    Optional<ApiCredential> findByPublicPrefix(String publicPrefix);

    /** A no-op in this service: last-used belongs to the merchant service's table. */
    void touchLastUsed(ApiCredentialId apiCredentialId, Instant usedAt);
}
