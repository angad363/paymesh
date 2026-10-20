package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.merchant.domain.ApiCredentialId;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issues and revokes merchant API credentials.
 *
 * <h2>THE SECRET IS RETURNED ONCE AND NEVER AGAIN</h2>
 *
 * Generated here, hashed, and the hash stored. The plaintext leaves in one response and there is no
 * recovery path -- a merchant who loses it issues a new key and revokes the old one. Same rule as
 * refresh tokens (V2), and for the same reason: a secret a database reader can use is a shared
 * password with extra steps.
 *
 * <h2>SHA-256, NOT BCRYPT, AND THAT IS DELIBERATE</h2>
 *
 * This hash is verified on <b>every API request</b>, where a deliberately slow KDF would be a
 * self-inflicted denial of service. bcrypt's cost exists to make GUESSING a low-entropy human
 * password expensive; this secret is 32 bytes from {@link SecureRandom}, which is not guessable at
 * any hash speed. Using bcrypt here would buy nothing and cost every request.
 * <p>
 * The password hashing in Identity is bcrypt and must stay bcrypt, because a human chose that
 * input. The difference between the two is the entropy of what is being hashed, not carelessness.
 *
 * <h2>EVERY CHANGE ALSO EMITS AN EVENT (ADR-048 section 4, option 3)</h2>
 *
 * {@code merchant.api_credential.issued} and {@code .revoked} are appended in the SAME transaction
 * as the credential write ({@link TransactionTemplate}, ADR-010), so a rolled-back append rolls the
 * credential change back with it. Payment's {@code api_credential_ref} projection is fed from them,
 * which is what lets it verify an ApiKey locally. The payload carries the SHA-256 secret hash
 * (never the secret): the second copy of that hash is the stated tradeoff of option 3.
 */
public final class IssueApiCredentialService {

    private static final Logger log = LoggerFactory.getLogger(IssueApiCredentialService.class);

    private static final String PREFIX = "ak_";
    private static final int PREFIX_BYTES = 12;
    private static final int SECRET_BYTES = 32;
    private static final int EVENT_VERSION = 1;

    private final ApiCredentialRepository credentials;
    /** v1 of the credential envelope (ADR-036 versioning rule). */

    private final SecureRandom random;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public IssueApiCredentialService(
        ApiCredentialRepository credentials,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.credentials = credentials;
        this.random = new SecureRandom();
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @return the credential and its plaintext secret, formatted {@code ak_<prefix>.<secret>}.
     *     One token rather than two fields: a caller who has to assemble two values will eventually
     *     assemble them wrong, and a single opaque string is also what every other platform's key
     *     looks like.
     */
    public ApiCredentialSecret issue(MerchantId merchantId, CallerRole role, String label) {
        String publicPrefix = PREFIX + randomToken(PREFIX_BYTES);
        String secret = randomToken(SECRET_BYTES);

        Instant now = Instant.now(clock);

        ApiCredential saved = transactions.execute(status -> {
            ApiCredential issued = credentials.save(ApiCredential.issue(
                merchantId, publicPrefix, ApiCredentialSecrets.hash(secret), role, label, now
            ));
            outbox.append(credentialEvent("merchant.api_credential.issued", issued, now));
            return issued;
        });

        log.info(
            "Issued API credential apiCredentialId={} merchantId={} role={} label={}",
            saved.apiCredentialId().value(), merchantId.value(), role, saved.label()
        );

        return new ApiCredentialSecret(saved, publicPrefix + "." + secret);
    }

    public ApiCredential revoke(MerchantId merchantId, ApiCredentialId apiCredentialId) {
        ApiCredential credential = credentials.findById(merchantId, apiCredentialId)
            .orElseThrow(() -> new ApiCredentialNotFoundException(apiCredentialId.value()));

        Instant now = Instant.now(clock);

        ApiCredential revoked = transactions.execute(status -> {
            ApiCredential saved = credentials.save(credential.revoke(now));
            outbox.append(credentialEvent("merchant.api_credential.revoked", saved, now));
            return saved;
        });

        log.warn(
            "Revoked API credential apiCredentialId={} merchantId={}",
            apiCredentialId.value(), merchantId.value()
        );

        return revoked;
    }

    public List<ApiCredential> list(MerchantId merchantId) {
        return credentials.findByMerchant(merchantId);
    }

    private static OutboxEvent credentialEvent(String eventType, ApiCredential credential, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiCredentialId", credential.apiCredentialId().value());
        payload.put("merchantId", credential.merchantId().value());
        payload.put("publicPrefix", credential.publicPrefix());
        payload.put("secretHash", credential.secretHash());
        payload.put("role", credential.role().name());
        payload.put("revokedAt", credential.revokedAt() == null ? null : credential.revokedAt().toString());
        payload.put("eventVersion", EVENT_VERSION);

        return new OutboxEvent(
            EventId.generate(),
            credential.merchantId(),
            "MERCHANT",
            credential.merchantId().value(),
            eventType,
            EVENT_VERSION,
            payload,
            occurredAt
        );
    }

    /** URL-safe, unpadded, so the whole token survives a config file and a query string unmangled. */
    private String randomToken(int bytes) {
        byte[] material = new byte[bytes];
        random.nextBytes(material);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }
}
