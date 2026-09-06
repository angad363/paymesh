package com.paymesh.simulator.infrastructure.http;

import com.paymesh.simulator.application.CallbackDelivery;
import com.paymesh.simulator.application.CallbackSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs one callback and puts it on the wire.
 *
 * <h2>The scheme, and it is not negotiable</h2>
 *
 * <pre>
 *   X-PayMesh-Signature: t=&lt;unix-seconds&gt;,v1=&lt;hex HMAC-SHA256 of (t + "." + body)&gt;
 * </pre>
 *
 * {@code ProviderCallbackSignatureFilter} is the authority and this must match it exactly. It
 * verifies with {@code MessageDigest.isEqual} against an HMAC over {@code t + "." + body} using
 * {@code paymesh.provider.callback-secret}, within ±300 seconds. A near miss here produces a 401
 * that reads like a bug in the receiver, and the sabotage list in ADR-017 exists because the two
 * most plausible near misses -- signing the body alone, and re-serializing after signing -- are both
 * invisible from this side.
 *
 * <h2>THE BYTES SIGNED ARE THE BYTES SENT</h2>
 *
 * The body arrives here as the string stored at enqueue time. It is encoded to UTF-8 <b>once</b>,
 * that byte array is what the HMAC covers, and that same byte array is what is posted. There is no
 * re-serialization, no object round trip and no {@code String} handed to a message converter that
 * might pick its own charset -- which is why the body is written as {@code byte[]} rather than as a
 * {@code String}.
 *
 * <h2>The timestamp is taken HERE, at delivery</h2>
 *
 * Not at enqueue time. A callback deliberately delayed past the receiver's ±300s window would
 * otherwise arrive carrying a signature older than the window allows and be refused as a replay --
 * the delayed-callback feature failing as a security error. {@code occurred_at} (the provider's event
 * clock, which the ordering guard compares) and {@code t} (a freshness stamp) are different facts
 * produced at different moments, and this is where that distinction is made concrete.
 */
public final class HttpCallbackSender implements CallbackSender {

    private static final Logger log = LoggerFactory.getLogger(HttpCallbackSender.class);

    private static final String SIGNATURE_HEADER = "X-PayMesh-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private final RestClient restClient;
    private final String callbackUrl;
    private final String payoutCallbackUrl;
    private final String signingSecret;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HttpCallbackSender(
        RestClient restClient,
        String callbackUrl,
        String payoutCallbackUrl,
        String signingSecret,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.restClient = restClient;
        this.callbackUrl = callbackUrl;
        this.payoutCallbackUrl = payoutCallbackUrl;
        this.signingSecret = signingSecret;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CallbackDelivery send(
        String body, com.paymesh.simulator.domain.CallbackTarget target
    ) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now(clock).getEpochSecond();
        String signature = "t=" + timestamp + ",v1=" + hmac(timestamp + "." + body);

        try {
            return restClient.post()
                // THE ONLY THING THE TARGET CHANGES. Both routes verify the same signature with
                // the same secret, so a payout callback is a payment callback pointed elsewhere.
                .uri(target == com.paymesh.simulator.domain.CallbackTarget.PAYOUT
                    ? payoutCallbackUrl
                    : callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, signature)
                .body(bytes)
                // exchange with conversion off: a non-2xx is DATA here, not an exception. A 404 in
                // particular is a deliberate request to retry (ADR-012 section 7), and turning it
                // into a thrown error would bury that in a catch block beside genuine transport
                // failures.
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
                    ? CallbackDelivery.accepted(
                        response.getStatusCode().value(), outcomeIn(response.bodyTo(String.class))
                    )
                    : CallbackDelivery.refused(response.getStatusCode().value()), false);
        } catch (RestClientException unreachable) {
            // No answer at all: connection refused, DNS, read timeout. Indistinguishable from a lost
            // response, which is exactly why delivery is at-least-once and the receiver deduplicates.
            log.warn("Provider callback delivery to {} failed: {}", callbackUrl, unreachable.getMessage());

            return CallbackDelivery.refused(null);
        }
    }

    /**
     * APPLIED, DUPLICATE, IGNORED_STALE or IGNORED_TERMINAL, out of the 200 body.
     * <p>
     * The status code is the retry signal and the body is the detail (ADR-012 section 6). Recording
     * only the code would leave the simulator unable to observe whether the duplicate it carefully
     * constructed actually deduplicated -- which is half of what its tests assert on.
     * <p>
     * An unparseable body is not a failed delivery. PayMesh answered 2xx, so the callback landed;
     * the missing detail is a reporting gap, and treating it as a failure would redeliver an event
     * that was already applied for no reason.
     */
    private String outcomeIn(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        try {
            JsonNode outcome = objectMapper.readTree(responseBody).get("outcome");

            return outcome == null || outcome.isNull() ? null : outcome.asString();
        } catch (RuntimeException notJson) {
            log.warn("Provider callback response was not readable JSON: {}", notJson.getMessage());

            return null;
        }
    }

    private String hmac(String signedString) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));

            return HexFormat.of().formatHex(
                mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(ALGORITHM + " is required by every JVM", impossible);
        }
    }
}
