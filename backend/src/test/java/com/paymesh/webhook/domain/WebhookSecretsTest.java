package com.paymesh.webhook.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE KNOWN-ANSWER VECTOR, AND WHY IT IS THE MOST IMPORTANT TEST IN THIS CAPABILITY.
 *
 * <p>Every other test here could derive and verify with the same formula and pass no matter what
 * that formula is. Sign with one function, check with the same function, and a typo in the
 * {@code info} string is invisible -- while every merchant's verifier in the world starts
 * rejecting every delivery, because theirs is a different implementation reading ADR-028.
 *
 * <p>{@link #derivesTheFrozenKnownAnswerVector()} is the only test that would notice. It hard-codes
 * bytes generated once and frozen. <b>If it fails, the implementation changed and the
 * implementation is wrong. Do not regenerate the expected value to make it pass</b> -- that
 * converts the one test protecting the wire contract into a test that agrees with whatever the code
 * currently does.
 */
class WebhookSecretsTest {

    /** Exactly 32 ASCII bytes, so it satisfies RFC 5869 §3.3 without a Base64 round trip. */
    private static final byte[] MASTER_KEY =
        "paymesh-test-master-key-32-bytes".getBytes(StandardCharsets.US_ASCII);

    private static final EndpointId ENDPOINT =
        EndpointId.from("whe_00000000-0000-4000-8000-000000000001");

    @Test
    void derivesTheFrozenKnownAnswerVector() {
        assertThat(WebhookSecrets.derive(MASTER_KEY, ENDPOINT, 1))
            .isEqualTo("whsec_FSviFzV65R0qahrGjj1MseU2BmYQkc3rL9OriJPlsqI");
    }

    /** The second half of the vector: rotation must actually change the bytes. */
    @Test
    void derivesADifferentFrozenSecretForTheNextVersion() {
        assertThat(WebhookSecrets.derive(MASTER_KEY, ENDPOINT, 2))
            .isEqualTo("whsec_Jfw3jylWLHXSqFjcJhBGKicygTdWQ14fmR6fg5KCDyU");
    }

    /**
     * One merchant's secret must be useless against another endpoint. This is the property that
     * makes a per-tenant secret worth having at all -- open item 8 exists because the provider
     * callback path has one global secret instead.
     */
    @Test
    void derivesADifferentFrozenSecretForADifferentEndpoint() {
        assertThat(WebhookSecrets.derive(
            MASTER_KEY, EndpointId.from("whe_00000000-0000-4000-8000-000000000002"), 1
        )).isEqualTo("whsec_QhFfv_GCGBHpDnmVoH84FoFsMWyZEGlQoFkLY0zHNCA");
    }

    @Test
    void derivesTheSameSecretEveryTimeForTheSameInputs() {
        assertThat(WebhookSecrets.derive(MASTER_KEY, ENDPOINT, 7))
            .isEqualTo(WebhookSecrets.derive(MASTER_KEY, ENDPOINT, 7));
    }

    @Test
    void derivesADifferentSecretUnderADifferentMasterKey() {
        byte[] other = "a-completely-different-32-byte-k".getBytes(StandardCharsets.US_ASCII);

        assertThat(WebhookSecrets.derive(other, ENDPOINT, 1))
            .isNotEqualTo(WebhookSecrets.derive(MASTER_KEY, ENDPOINT, 1));
    }

    /**
     * A SHORT MASTER KEY IS REFUSED, BECAUSE IT WITHDRAWS THE LICENCE THE WHOLE SCHEME RESTS ON.
     *
     * <p>Skipping HKDF-Extract is only sound when the input is already uniformly random and
     * fixed-length (RFC 5869 §3.3). A typed passphrase in the configuration property is neither,
     * and nothing about the resulting secret would look wrong -- it would still be 32 plausible
     * bytes. Failing loudly is the only way this is ever noticed.
     */
    @Test
    void refusesAMasterKeyTooShortToSkipExtract() {
        byte[] tooShort = "hunter2".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> WebhookSecrets.derive(tooShort, ENDPOINT, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void refusesANullMasterKey() {
        assertThatThrownBy(() -> WebhookSecrets.derive(null, ENDPOINT, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAVersionBelowOne() {
        assertThatThrownBy(() -> WebhookSecrets.derive(MASTER_KEY, ENDPOINT, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("starts at 1");
    }

    /** The prefix is part of the contract a merchant pattern-matches on, not decoration. */
    @Test
    void prefixesEverySecretSoItIsRecognisableInASupportTicket() {
        assertThat(WebhookSecrets.derive(MASTER_KEY, ENDPOINT, 3)).startsWith("whsec_");
    }
}
