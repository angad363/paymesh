package com.paymesh.merchant.infrastructure.security;

import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.application.ApiCredentialSecrets;
import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.shared.security.ApiKeyAuthenticator;
import com.paymesh.shared.security.ApiKeyIdentity;

import java.time.Clock;
import java.time.Duration;
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
 * <h2>Last-used is throttled, best-effort, and allowed to fail</h2>
 *
 * Writing it on every request would put a row update -- with its WAL write and its row lock -- on
 * the authentication path of every single call, and a busy key's row would be the hottest in the
 * system. It is written at most once per {@link #TOUCH_INTERVAL} instead.
 * <p>
 * That is not a correctness compromise, because of what the column is for: finding keys nobody has
 * rotated. Ten minutes of staleness makes no difference to that question, and the alternative costs
 * a write per payment.
 * <p>
 * It is also allowed to fail, swallowed to a debug line in the adapter. If writing an observability
 * column fails, the correct outcome is a stale timestamp, not a rejected payment.
 */
public final class ApiCredentialAuthenticator implements ApiKeyAuthenticator {

    /**
     * How stale {@code last_used_at} may get. Ten minutes answers "has anyone used this key
     * lately" exactly as well as ten seconds would, at a fraction of the writes.
     */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(10);

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

        verified.filter(ApiCredentialAuthenticator::isStale).ifPresent(credential ->
            credentials.touchLastUsed(credential.apiCredentialId(), Instant.now(clock)));

        return verified.map(credential -> new ApiKeyIdentity(
            // The credential's id, not a user's. An API key is not a person, and attributing a
            // machine's writes to whoever created the key puts the wrong name in every audit row.
            credential.apiCredentialId().value(),
            credential.role(),
            credential.merchantId()
        ));
    }

    /** True when the recorded use is old enough to be worth another write. */
    private static boolean isStale(ApiCredential credential) {
        return credential.lastUsedAt() == null
            || credential.lastUsedAt().isBefore(Instant.now().minus(TOUCH_INTERVAL));
    }
}
