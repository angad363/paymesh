package com.paymesh.webhook.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Derives an endpoint's signing secret instead of storing one. ADR-028 §2.
 *
 * <h2>THE COLUMN THAT IS NOT IN V24 IS THE POINT</h2>
 *
 * Every conventional webhook schema carries an encrypted per-endpoint secret. In this codebase that
 * is not a column, it is a subsystem: there is no {@code Cipher}, no AES and no key management
 * anywhere in twenty-five migrations. {@code ApiCredential} (ADR-022) looks like the precedent and
 * is not -- it stores a <b>hash</b>, which works only because verifying an inbound secret never
 * needs the plaintext back. A signing secret must be <b>reproduced</b> on every send, so a hash is
 * useless and the real choice was reversible storage versus derivation.
 * <p>
 * Derivation wins on an argument about blast radius rather than elegance: one leaked master key and
 * one leaked encryption key expose exactly the same set of secrets, and only one of the two options
 * requires building, reviewing and rotating a cipher subsystem.
 *
 * <h2>THIS IS ONE BLOCK OF HKDF-EXPAND, AND DELIBERATELY NOT {@code javax.crypto.KDF}</h2>
 *
 * <b>JDK 21 has no HKDF.</b> {@code javax.crypto.KDF} and {@code HKDFParameterSpec} are JEP 478
 * (preview, JDK 24) and JEP 510 (final, JDK 25); this project pins Java 21 and carries no
 * BouncyCastle or Tink. An earlier draft of ADR-028's design document claimed otherwise, and that
 * error mattered -- believing it would have meant adding a crypto dependency, which is the exact
 * cost this decision exists to avoid.
 * <p>
 * What is built instead is {@code T(1)} of RFC 5869 §2.3: a single {@code Mac} call, the same class
 * {@code ProviderCallbackSignatureFilter} already uses. <b>Extract is skipped</b>, which RFC 5869
 * §3.3 permits when the input keying material is already a uniformly-random fixed-length key --
 * and that precondition is enforced in {@link #requireStrongMasterKey}, not assumed, because a
 * typed passphrase in the master-key property would silently withdraw the licence.
 *
 * <h2>PINNED, BECAUSE A SILENT CHANGE BREAKS EVERY MERCHANT AT ONCE</h2>
 *
 * SHA-256, no salt, the {@code info} string exactly as built below, 32 bytes out, Base64 URL-safe
 * without padding, {@code pmsec_} prefix. {@code WebhookSecretsTest} asserts a frozen known-answer
 * vector against all of it. <b>If that test fails, the behaviour is wrong -- do not regenerate the
 * vector to match.</b> Every other test in the suite would derive and verify with the same changed
 * formula and notice nothing, while every merchant's verifier in the world started rejecting every
 * delivery.
 */
public final class WebhookSecrets {

    private static final String ALGORITHM = "HmacSHA256";

    /** Namespaced so this master key can never collide with another use of the same bytes. */
    private static final String INFO_PREFIX = "paymesh.webhook.v1|";

    /**
     * What a merchant sees -- recognisable in a log or a support ticket.
     *
     * <h2>PAYMESH'S OWN, AND DELIBERATELY NOT STRIPE'S {@code whsec_} (ADR-028 §2.1)</h2>
     *
     * <p>An earlier version of this class used {@code whsec_} because it is the prefix an
     * integrator recognises. GitHub's secret scanner recognises it too, and classified this
     * capability's committed known-answer test vectors as leaked <b>Stripe</b> webhook signing
     * secrets. That would repeat for every scanner, on every merchant who ever commits one of
     * these, forever -- and it labels a PayMesh secret with another company's name while doing it.
     */
    private static final String SECRET_PREFIX = "pmsec_";

    /**
     * RFC 5869 §3.3's precondition for skipping Extract. Thirty-two bytes is the output length of
     * the PRF, so a shorter key would be the weakest link in the whole scheme.
     */
    private static final int MINIMUM_MASTER_KEY_BYTES = 32;

    /** The single-block counter byte from RFC 5869 §2.3. Not decoration -- omitting it is wrong. */
    private static final byte COUNTER = 0x01;

    private WebhookSecrets() {
    }

    /**
     * The secret an endpoint signs with at a given version.
     *
     * @param masterKey raw bytes, at least 32 of them, from configuration
     * @param endpointId the {@code whe_} id, which is what makes one endpoint's secret useless
     *     against another
     * @param secretVersion in decimal; incrementing it is the whole of rotation
     */
    public static String derive(byte[] masterKey, EndpointId endpointId, int secretVersion) {
        requireStrongMasterKey(masterKey);

        if (endpointId == null) {
            throw new IllegalArgumentException("An endpoint identifier is required to derive");
        }

        if (secretVersion < 1) {
            throw new IllegalArgumentException("A secret version starts at 1");
        }

        String info = INFO_PREFIX + endpointId.value() + "|" + secretVersion;

        byte[] derived = expandOneBlock(masterKey, info);

        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    /**
     * Refuses a master key that is not what §3.3 assumes.
     *
     * <p>Separated from {@link #derive} and public so configuration can fail at startup rather than
     * on the first delivery -- the same instinct as {@code DevelopmentSecretGuard}. A platform that
     * boots and then cannot sign is worse than one that refuses to boot.
     */
    public static void requireStrongMasterKey(byte[] masterKey) {
        if (masterKey == null || masterKey.length < MINIMUM_MASTER_KEY_BYTES) {
            throw new IllegalArgumentException(
                "The webhook signing master key must be at least " + MINIMUM_MASTER_KEY_BYTES
                    + " bytes of raw entropy. Below that, RFC 5869 no longer permits skipping "
                    + "HKDF-Extract and the derivation this platform signs with is not sound."
            );
        }
    }

    private static byte[] expandOneBlock(byte[] masterKey, String info) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(masterKey, ALGORITHM));

            mac.update(info.getBytes(StandardCharsets.US_ASCII));
            mac.update(COUNTER);

            return mac.doFinal();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(ALGORITHM + " is required by every JVM", impossible);
        }
    }
}
