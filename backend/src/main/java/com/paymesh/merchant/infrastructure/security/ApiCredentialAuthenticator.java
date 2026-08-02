package com.paymesh.merchant.infrastructure.security;

import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.application.ApiCredentialSecrets;
import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.shared.security.ApiKeyAuthenticator;
import com.paymesh.shared.security.ApiKeyIdentity;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The Merchant module answering the platform's question. See {@link ApiKeyAuthenticator}.
 *
 * <h2>THE LOOKUP IS BY PUBLIC PREFIX, THE COMPARISON IS CONSTANT-TIME</h2>
 *
 * The prefix finds the row; the secret is then compared through
 * {@link ApiCredentialSecrets#matches}, which uses {@code MessageDigest.isEqual} and does not
 * short-circuit on the first differing byte. A plain {@code equals} would leak the secret one byte
 * at a time to anyone who can measure response time.
 *
 * <h2>Four failures, one answer</h2>
 *
 * Malformed, unknown prefix, revoked, wrong secret -- all return empty. Distinguishing them would
 * confirm which prefixes exist, and a revoked key answering differently from an unknown one tells
 * an attacker they once had something real.
 *
 * <h2>Last-used is recorded, and it is allowed to fail</h2>
 *
 * Best effort, in its own transaction, with failure swallowed to a debug line. It is an operational
 * convenience for spotting unrotated keys; if writing it fails the correct outcome is a stale
 * timestamp, not a rejected payment. Letting it throw would make an observability column able to
 * take the platform down.
 */
public final class ApiCredentialAuthenticator implements ApiKeyAuthenticator {

    private final ApiCredentialRepository credentials;
    private final Clock clock;

    public ApiCredentialAuthenticator(ApiCredentialRepository credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    @Override
    public Optional<ApiKeyIdentity> authenticate(String presented) {
        int separator = presented.indexOf('.');

        if (separator < 1 || separator == presented.length() - 1) {
            return Optional.empty();
        }

        String publicPrefix = presented.substring(0, separator);
        String secret = presented.substring(separator + 1);

        Optional<ApiCredential> verified = credentials.findByPublicPrefix(publicPrefix)
            .filter(ApiCredential::isLive)
            .filter(credential -> ApiCredentialSecrets.matches(secret, credential.secretHash()));

        verified.ifPresent(credential ->
            credentials.touchLastUsed(credential.apiCredentialId(), Instant.now(clock)));

        return verified.map(credential -> new ApiKeyIdentity(
            // The credential's id, not a user's. An API key is not a person, and attributing a
            // machine's writes to whoever created the key puts the wrong name in every audit row.
            credential.apiCredentialId().value(),
            credential.role(),
            credential.merchantId()
        ));
    }
}
