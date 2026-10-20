package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.merchant.domain.ApiCredentialId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

public interface ApiCredentialRepository {

    ApiCredential save(ApiCredential credential);

    /**
     * By the public half, which is unique platform-wide.
     * <p>
     * NOT merchant-scoped, and it cannot be: authentication is what establishes the merchant, so
     * there is no tenant to scope by yet. That is exactly why {@code public_prefix} is globally
     * unique -- a per-merchant key space would force the lookup to guess a tenant first, and the
     * guess itself would be a cross-tenant oracle.
     */
    Optional<ApiCredential> findByPublicPrefix(String publicPrefix);

    Optional<ApiCredential> findById(MerchantId merchantId, ApiCredentialId apiCredentialId);

    List<ApiCredential> findByMerchant(MerchantId merchantId);

    /** Best-effort, out of band. See {@code IssueApiCredentialService} for why it is not on the hot path. */
    void touchLastUsed(ApiCredentialId apiCredentialId, java.time.Instant usedAt);
}
