package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.merchant.domain.ApiCredentialId;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

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
 */
public final class IssueApiCredentialService {

    private static final Logger log = LoggerFactory.getLogger(IssueApiCredentialService.class);

    private static final String PREFIX = "ak_";
    private static final int PREFIX_BYTES = 12;
    private static final int SECRET_BYTES = 32;

    private final ApiCredentialRepository credentials;
    private final SecureRandom random;
    private final Clock clock;

    public IssueApiCredentialService(ApiCredentialRepository credentials, Clock clock) {
        this.credentials = credentials;
        this.random = new SecureRandom();
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

        ApiCredential saved = credentials.save(ApiCredential.issue(
            merchantId, publicPrefix, ApiCredentialSecrets.hash(secret), role, label,
            Instant.now(clock)
        ));

        log.info(
            "Issued API credential apiCredentialId={} merchantId={} role={} label={}",
            saved.apiCredentialId().value(), merchantId.value(), role, saved.label()
        );

        return new ApiCredentialSecret(saved, publicPrefix + "." + secret);
    }

    public ApiCredential revoke(MerchantId merchantId, ApiCredentialId apiCredentialId) {
        ApiCredential credential = credentials.findById(merchantId, apiCredentialId)
            .orElseThrow(() -> new ApiCredentialNotFoundException(apiCredentialId.value()));

        ApiCredential revoked = credentials.save(credential.revoke(Instant.now(clock)));

        log.warn(
            "Revoked API credential apiCredentialId={} merchantId={}",
            apiCredentialId.value(), merchantId.value()
        );

        return revoked;
    }

    public List<ApiCredential> list(MerchantId merchantId) {
        return credentials.findByMerchant(merchantId);
    }

    /** URL-safe, unpadded, so the whole token survives a config file and a query string unmangled. */
    private String randomToken(int bytes) {
        byte[] material = new byte[bytes];
        random.nextBytes(material);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }
}
