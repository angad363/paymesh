package com.paymesh.merchant.domain;

import com.paymesh.merchant.application.ApiCredentialSecrets;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The credential aggregate and the hashing beneath it. Plain JUnit, no Spring. */
class ApiCredentialTest {

    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final Instant CREATED = Instant.parse("2026-08-02T10:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void issuesLiveAndUnrevoked() {
        ApiCredential credential = issue(CallerRole.MERCHANT_USER);

        assertThat(credential.isLive()).isTrue();
        assertThat(credential.revokedAt()).isNull();
        assertThat(credential.apiCredentialId().value()).startsWith("apc_");
    }

    @Test
    void revokesWithATimestampRatherThanADelete() {
        ApiCredential revoked = issue(CallerRole.MERCHANT_ADMIN).revoke(CREATED.plusSeconds(60));

        assertThat(revoked.isLive()).isFalse();
        assertThat(revoked.revokedAt()).isEqualTo(CREATED.plusSeconds(60));
        assertThat(revoked.secretHash())
            .as("the hash survives revocation; the row is history, not a tombstone")
            .isEqualTo(HASH);
    }

    @Test
    void refusesASecondRevocation() {
        ApiCredential revoked = issue(CallerRole.MERCHANT_ADMIN).revoke(CREATED.plusSeconds(60));

        assertThatThrownBy(() -> revoked.revoke(CREATED.plusSeconds(120)))
            .isInstanceOf(ApiCredentialAlreadyRevokedException.class);
    }

    /**
     * A PLATFORM_ADMIN KEY WOULD LET A STRING IN A CONFIG FILE SUSPEND MERCHANTS AND APPROVE KYC.
     * No machine needs that, and {@code ck_api_credentials_role} refuses it at the schema too.
     */
    @Test
    void refusesAPlatformAdminCredential() {
        assertThatThrownBy(() -> issue(CallerRole.PLATFORM_ADMIN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MERCHANT_ADMIN or MERCHANT_USER");
    }

    @Test
    void refusesAServiceAccountCredential() {
        assertThatThrownBy(() -> issue(CallerRole.SERVICE_ACCOUNT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** "Which of these six keys is the CI one" is asked at the moment one must be revoked fast. */
    @Test
    void requiresALabel() {
        assertThatThrownBy(() -> ApiCredential.issue(
            MERCHANT, "ak_x", HASH, CallerRole.MERCHANT_USER, "  ", CREATED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("labelled");
    }

    @Test
    void refusesAHashThatIsNotSha256Hex() {
        assertThatThrownBy(() -> ApiCredential.issue(
            MERCHANT, "ak_x", "not-a-hash", CallerRole.MERCHANT_USER, "CI", CREATED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("64 hex");
    }

    // --- the hashing ----------------------------------------------------------------------------

    @Test
    void hashesToSixtyFourHexCharacters() {
        assertThat(ApiCredentialSecrets.hash("hunter2")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void matchesOnlyTheSecretItHashed() {
        String hash = ApiCredentialSecrets.hash("correct-secret");

        assertThat(ApiCredentialSecrets.matches("correct-secret", hash)).isTrue();
        assertThat(ApiCredentialSecrets.matches("wrong-secret", hash)).isFalse();
        assertThat(ApiCredentialSecrets.matches("", hash)).isFalse();
    }

    /**
     * A NEAR MISS MUST NOT MATCH, which is the property a short-circuiting comparison would still
     * have -- what it would additionally have is a timing signal. This pins the correctness half;
     * the constant-time half is a property of {@code MessageDigest.isEqual} and is asserted by
     * reading the implementation rather than by timing a JVM.
     */
    @Test
    void refusesASecretDifferingOnlyInTheLastCharacter() {
        String hash = ApiCredentialSecrets.hash("secretA");

        assertThat(ApiCredentialSecrets.matches("secretB", hash)).isFalse();
    }

    private static ApiCredential issue(CallerRole role) {
        return ApiCredential.issue(MERCHANT, "ak_prefix", HASH, role, "CI", CREATED);
    }
}
