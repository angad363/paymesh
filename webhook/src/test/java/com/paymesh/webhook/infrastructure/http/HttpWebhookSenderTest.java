package com.paymesh.webhook.infrastructure.http;

import com.paymesh.webhook.application.WebhookSender;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * THE SIGNATURE A MERCHANT'S VERIFIER HAS TO REPRODUCE, asserted against the actual request.
 *
 * <p>{@code MockRestServiceServer} rather than a fake sender: the thing worth checking here is what
 * lands on the wire -- the header, the byte-for-byte body, the charset -- and a test that called an
 * internal method would pass while a message converter quietly re-encoded the payload.
 *
 * <p>The URL is a public literal so {@link PrivateAddressGuard} lets it through; the guard has its
 * own tests.
 */
class HttpWebhookSenderTest {

    private static final String MASTER_KEY = "paymesh-test-master-key-32-bytes";
    private static final String URL = "https://93.184.216.34/hooks";
    private static final String PAYLOAD = "{\"id\":\"whv_x\",\"amountMinor\":500000}";

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private static final long TIMESTAMP = NOW.getEpochSecond();

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private final WebhookSender sender = new HttpWebhookSender(
        builder.build(),
        new PrivateAddressGuard(false),
        MASTER_KEY.getBytes(StandardCharsets.UTF_8),
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void signsTheExactBytesItSends() {
        WebhookEndpoint endpoint = endpoint();

        server.expect(requestTo(URL))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(content().bytes(PAYLOAD.getBytes(StandardCharsets.UTF_8)))
            .andExpect(header("X-PayMesh-Signature", expectedSignature(endpoint, 1)))
            .andRespond(withStatus(HttpStatus.OK).body("received"));

        WebhookSender.WebhookSendResult result = sender.send(endpoint, PAYLOAD);

        assertThat(result.accepted()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.excerpt()).isEqualTo("received");

        server.verify();
    }

    /** The charset is spelled out: a receiver that guessed would decode different bytes than it verified. */
    @Test
    void declaresUtf8OnTheContentType() {
        server.expect(requestTo(URL))
            .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"))
            .andRespond(withStatus(HttpStatus.OK).body("ok"));

        sender.send(endpoint(), PAYLOAD);

        server.verify();
    }

    /**
     * INSIDE A ROTATION WINDOW THERE ARE TWO {@code v1=} VALUES, current first, so a merchant who
     * rotated but has not deployed their new verifier still has one that matches.
     *
     * <p>Also the point at which this format becomes a SUPERSET of what
     * {@code ProviderCallbackSignatureFilter} parses -- that one keeps the last v1 it sees, and a
     * merchant must check whether ANY matches. ADR-028 §4.
     */
    @Test
    void emitsBothSignaturesInsideARotationWindow() {
        WebhookEndpoint rotated = endpoint().rotateSecret(1, NOW);

        server.expect(requestTo(URL))
            .andExpect(header("X-PayMesh-Signature", expectedSignature(rotated, 2, 1)))
            .andRespond(withStatus(HttpStatus.OK).body("ok"));

        sender.send(rotated, PAYLOAD);

        server.verify();
    }

    @Test
    void treatsANonSuccessAsAFailedAttemptRatherThanAnException() {
        server.expect(requestTo(URL))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("try later"));

        WebhookSender.WebhookSendResult result = sender.send(endpoint(), PAYLOAD);

        assertThat(result.accepted()).isFalse();
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.excerpt()).isEqualTo("try later");
    }

    /**
     * A MERCHANT RETURNING MEGABYTES MUST NOT BECOME PAYMESH'S MEMORY PROBLEM. Capped at the READ,
     * not after it -- truncating afterwards would already have allocated the whole thing.
     */
    @Test
    void capsHowMuchOfTheResponseItReads() {
        server.expect(requestTo(URL))
            .andRespond(withStatus(HttpStatus.OK).body("x".repeat(50_000)));

        WebhookSender.WebhookSendResult result = sender.send(endpoint(), PAYLOAD);

        assertThat(result.excerpt()).hasSize(HttpWebhookSender.MAX_RESPONSE_BYTES);
    }

    /** No socket is opened at all: the guard answers before the client is asked. */
    @Test
    void refusesAPrivateAddressWithoutSendingAnything() {
        WebhookEndpoint internal = WebhookEndpoint.register(
            "mrc_550e8400-e29b-41d4-a716-446655440000",
            "https://169.254.169.254/latest/meta-data/",
            List.of("payment.succeeded"),
            NOW
        );

        WebhookSender.WebhookSendResult result = sender.send(internal, PAYLOAD);

        assertThat(result.accepted()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.excerpt()).contains("non-public address");

        server.verify();
    }

    private static WebhookEndpoint endpoint() {
        return WebhookEndpoint.register(
            "mrc_550e8400-e29b-41d4-a716-446655440000", URL, List.of("payment.succeeded"), NOW
        );
    }

    /** Recomputed here rather than copied, so the test states the scheme instead of the answer. */
    private static String expectedSignature(WebhookEndpoint endpoint, int... versions) {
        StringBuilder header = new StringBuilder("t=" + TIMESTAMP);

        for (int version : versions) {
            String secret = WebhookSecrets.derive(
                MASTER_KEY.getBytes(StandardCharsets.UTF_8), endpoint.endpointId(), version
            );

            header.append(",v1=").append(hmac(secret, TIMESTAMP + "." + PAYLOAD));
        }

        return header.toString();
    }

    private static String hmac(String secret, String signedString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            return HexFormat.of().formatHex(mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
