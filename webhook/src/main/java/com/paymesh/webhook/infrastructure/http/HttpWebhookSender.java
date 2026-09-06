package com.paymesh.webhook.infrastructure.http;

import com.paymesh.webhook.application.WebhookSender;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.StringJoiner;

/**
 * Signs one webhook and puts it on the wire.
 *
 * <h2>The scheme</h2>
 *
 * <pre>
 *   X-PayMesh-Signature: t=&lt;unix-seconds&gt;,v1=&lt;hex HMAC-SHA256 of (t + "." + body)&gt;
 * </pre>
 *
 * The same shape {@code ProviderCallbackSignatureFilter} verifies inbound, with two differences that
 * matter. The key is the endpoint's derived secret rather than one global value -- this is the first
 * genuinely per-tenant signing secret in the codebase. And inside a rotation window there are
 * <b>two</b> {@code v1=} values, the current version first, so a merchant who has rotated but not
 * yet deployed their new verifier still has one that matches.
 * <p>
 * <b>That makes this format a superset of what the inbound filter parses.</b> That filter keeps the
 * last {@code v1} it sees; a merchant must check whether ANY matches. ADR-028 §4 records the
 * asymmetry, because the natural mistake is to assume the two formats are the same format.
 *
 * <h2>THE BYTES SIGNED ARE THE BYTES SENT</h2>
 *
 * The payload arrives as the string stored in {@code webhook_events.payload}. It is encoded to UTF-8
 * once, that array is what the HMAC covers, and that same array is posted -- as {@code byte[]}, so
 * no message converter can re-serialize it or pick its own charset. A replay therefore resends
 * byte-identical content; only {@code t} and the signature over it differ, which is correct, because
 * the timestamp is a freshness stamp taken at delivery rather than a property of the event.
 *
 * <h2>The response is read with a cap</h2>
 *
 * At most {@value #MAX_RESPONSE_BYTES} bytes, before anything is stored or trimmed. A merchant
 * returning a 10 MB error page must not become PayMesh's memory problem, and truncating after
 * reading would already have allocated it.
 */
public final class HttpWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookSender.class);

    private static final String SIGNATURE_HEADER = "X-PayMesh-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    /** 4 KiB. Enough of an error page to debug from, small enough that a hostile one is harmless. */
    static final int MAX_RESPONSE_BYTES = 4096;

    private final RestClient restClient;
    private final PrivateAddressGuard addressGuard;
    private final byte[] masterKey;
    private final Clock clock;

    public HttpWebhookSender(
        RestClient restClient,
        PrivateAddressGuard addressGuard,
        byte[] masterKey,
        Clock clock
    ) {
        this.restClient = restClient;
        this.addressGuard = addressGuard;
        this.masterKey = masterKey.clone();
        this.clock = clock;
    }

    /**
     * The wired-up sender, built here rather than in {@code WebhookConfiguration} because
     * {@code followRedirects(NEVER)} is a security control and belongs beside the guard it completes.
     *
     * <h2>REDIRECTS ARE REFUSED, NOT FOLLOWED</h2>
     *
     * Without this a merchant registers a public HTTPS URL that passes every check and answers
     * {@code 302 Location: http://169.254.169.254/}, and the client walks straight past the address
     * guard on PayMesh's behalf. It is the cheap version of the DNS race
     * {@link PrivateAddressGuard} documents, and unlike that one it costs a single line to close.
     * A 3xx becomes an ordinary non-2xx: the delivery fails and is retried.
     */
    public static HttpWebhookSender create(
        String masterKey,
        boolean allowPrivateAddresses,
        Duration connectTimeout,
        Duration readTimeout,
        Clock clock
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(connectTimeout)
            .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return new HttpWebhookSender(
            RestClient.builder().requestFactory(requestFactory).build(),
            new PrivateAddressGuard(allowPrivateAddresses),
            masterKey.getBytes(StandardCharsets.UTF_8),
            clock
        );
    }

    @Override
    public WebhookSendResult send(WebhookEndpoint endpoint, String payload) {
        Instant now = Instant.now(clock);

        String rejection = addressGuard.rejectionFor(hostOf(endpoint.url()));

        if (rejection != null) {
            // Counts as a failed attempt rather than throwing: a merchant who registers a URL that
            // later resolves privately gets a delivery that fails and is visible to them, not a
            // dispatcher pass that dies on their behalf.
            log.warn("Refusing webhook delivery to {}: {}", endpoint.endpointId().value(), rejection);

            return WebhookSendResult.refused(null, rejection);
        }

        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        try {
            return restClient.post()
                .uri(endpoint.url())
                // charset spelled out, because the bytes above are UTF-8 and a receiver that
                // guessed otherwise would verify a signature over different bytes than it decoded.
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .header(SIGNATURE_HEADER, signature(endpoint, payload, now))
                .body(body)
                // Conversion off: a non-2xx is DATA here, not an exception. It is an ordinary
                // outcome that reschedules the delivery, and burying it in a catch beside genuine
                // transport failures would lose the status code that says which happened.
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
                    ? WebhookSendResult.accepted(response.getStatusCode().value(), excerpt(response))
                    : WebhookSendResult.refused(response.getStatusCode().value(), excerpt(response)),
                    false)
                ;
        } catch (RestClientException unreachable) {
            // No answer at all: connection refused, DNS, TLS, read timeout. No status code exists,
            // which is why the column is nullable.
            return WebhookSendResult.refused(null, unreachable.getMessage());
        }
    }

    /**
     * {@code t} is taken at DELIVERY, not at queue time. A delivery retried six hours later must
     * carry a fresh timestamp or every merchant enforcing a freshness window would reject the retry
     * as a replay -- the retry feature failing as a security error.
     */
    private String signature(WebhookEndpoint endpoint, String payload, Instant now) {
        long timestamp = now.getEpochSecond();
        String signedString = timestamp + "." + payload;

        StringJoiner header = new StringJoiner(",");
        header.add("t=" + timestamp);

        for (int version : endpoint.signingVersions(now)) {
            String secret = WebhookSecrets.derive(masterKey, endpoint.endpointId(), version);

            header.add("v1=" + hmac(secret, signedString));
        }

        return header.toString();
    }

    private static String excerpt(ClientHttpResponse response) {
        try {
            byte[] capped = response.getBody().readNBytes(MAX_RESPONSE_BYTES);

            return new String(capped, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            // The status code already said how it went. A body that will not read is a reporting
            // gap, not a failed delivery.
            return null;
        }
    }

    private static String hostOf(String url) {
        return java.net.URI.create(url).getHost();
    }

    private static String hmac(String secret, String signedString) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));

            return HexFormat.of().formatHex(
                mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(ALGORITHM + " is required by every JVM", impossible);
        }
    }
}
