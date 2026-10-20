package com.paymesh.shared.provider;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.api.CachedBodyHttpServletRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * THE AUTHENTICATION FOR THE PROVIDER CALLBACK ENDPOINT. There is no other.
 * <p>
 * {@code /internal/v1/provider-callbacks/**} is {@code permitAll()} on the Spring chain, because a
 * provider has no PayMesh account and holds no bearer token. It emphatically <b>must not</b> be
 * reachable with a merchant's token either: this route moves payments to SUCCEEDED, and a merchant
 * able to call it could mark their own payment collected -- a total compromise of the platform's
 * central invariant. The shared secret is what stands there instead, so everything about it matters.
 * <p>
 * The scheme is Stripe's, and deliberately so:
 * <pre>
 *   X-PayMesh-Signature: t=&lt;unix-seconds&gt;,v1=&lt;hex HMAC-SHA256 of (t + "." + body)&gt;
 * </pre>
 * <b>The timestamp is inside the signed string, which is the whole reason it is in the header.</b> A
 * signature over the body alone can be captured and replayed forever, and a timestamp that is not
 * signed can simply be rewritten by whoever captured it. Signing {@code t + "." + body} means a
 * captured signature is valid for exactly one body at exactly one moment.
 * <p>
 * Failures return 401 with no indication of WHICH check failed. "Bad timestamp" versus "bad
 * signature" tells an attacker whether they have the secret, and the caller who legitimately holds
 * it does not need to be told.
 *
 * <h2>IT LIVES IN {@code shared} BECAUSE TWO ROUTES NOW NEED IT, AND THERE MUST ONLY BE ONE OF IT</h2>
 *
 * It began in {@code payment.infrastructure.provider}, verifying exactly one path. Refund's callback
 * route needs the same scheme against the same secret, and the alternative to moving this class was
 * a second copy of it -- two implementations of the one check standing between a forged request and
 * a merchant's money, where fixing one silently leaves the other wrong. ADR-019.
 * <p>
 * Both the PATH it guards and the REQUEST ATTRIBUTE it writes the payload hash to are constructor
 * arguments rather than constants, so it names no capability. That is what lets it sit in
 * {@code shared} without {@code ModuleBoundaryTest} finding a capability import in there.
 */
public final class ProviderCallbackSignatureFilter extends OncePerRequestFilter {

    public static final String SIGNATURE_HEADER = "X-PayMesh-Signature";


    /**
     * How far out of step the provider's clock may be, in either direction.
     * <p>
     * Both directions, because a replay is not the only thing being refused: a signature stamped in
     * the FUTURE is one an attacker minted to stay valid past the window, and accepting it would
     * make the tolerance meaningless. Five minutes is long enough for ordinary clock drift and short
     * enough that a captured request is worthless by the time it is used.
     */
    private static final Duration TOLERANCE = Duration.ofSeconds(300);

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DIGEST = "SHA-256";

    private final String pathPrefix;
    private final String secret;
    private final String payloadHashAttribute;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @param pathPrefix the one path this instance guards, e.g.
     *     {@code /internal/v1/provider-callbacks}. One instance per callback route: a single filter
     *     matching both would still be correct today, because both routes share a secret, but it
     *     would silently keep working on the day they stop sharing one.
     * @param payloadHashAttribute the request attribute the raw-body hash is published under, which
     *     the owning controller reads back. A parameter rather than a constant so this class names
     *     no capability's controller.
     */
    public ProviderCallbackSignatureFilter(
        String pathPrefix,
        String secret,
        String payloadHashAttribute,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.pathPrefix = pathPrefix;
        this.secret = secret;
        this.payloadHashAttribute = payloadHashAttribute;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Everything else in the application authenticates the normal way and must not touch this. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathWithinApplication(request).startsWith(pathPrefix);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws IOException, ServletException {
        // Read once, kept: the handler needs the same bytes, and re-reading a servlet stream yields
        // nothing.
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        Signature signature = Signature.parse(request.getHeader(SIGNATURE_HEADER));

        if (signature == null || !signature.isFresh(Instant.now(clock))
            || !signature.matches(secret, cached.body())) {
            unauthorized(response);
            return;
        }

        // Hashed HERE because this is the last place the raw bytes exist. A hash taken later, from a
        // re-serialized record, would be a hash of Jackson's formatting rather than of what the
        // provider actually sent -- and payload_hash is the column an investigator uses to tell two
        // deliveries that share an event id apart.
        cached.setAttribute(payloadHashAttribute, sha256(cached.body()));
        chain.doFilter(cached, response);
    }

    /**
     * One answer for every failure. A filter runs outside every {@code @RestControllerAdvice}, so
     * the error body every other rejection produces has to be written by hand here.
     */
    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
            "PROVIDER_SIGNATURE_INVALID",
            "The provider callback signature could not be verified."
        ));
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();

        return contextPath == null || contextPath.isEmpty()
            ? uri
            : uri.substring(contextPath.length());
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST).digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(DIGEST + " is required by every JVM", impossible);
        }
    }

    /** {@code t=<unix-seconds>,v1=<hex>}, parsed. Null from {@link #parse} means unusable. */
    private record Signature(long timestamp, String hex) {

        static Signature parse(String header) {
            if (header == null || header.isBlank()) {
                return null;
            }

            Long timestamp = null;
            String hex = null;

            for (String element : header.split(",")) {
                String[] pair = element.trim().split("=", 2);

                if (pair.length != 2) {
                    continue;
                }

                if ("t".equals(pair[0])) {
                    timestamp = parseTimestamp(pair[1]);
                } else if ("v1".equals(pair[0])) {
                    hex = pair[1];
                }
            }

            return timestamp == null || hex == null || hex.isBlank()
                ? null
                : new Signature(timestamp, hex);
        }

        private static Long parseTimestamp(String value) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }

        boolean isFresh(Instant now) {
            return Math.abs(now.getEpochSecond() - timestamp) <= TOLERANCE.toSeconds();
        }

        /**
         * CONSTANT TIME, via {@link MessageDigest#isEqual}. A {@code String.equals} on an HMAC
         * returns as soon as two bytes differ, and the timing difference is enough to recover the
         * expected signature one byte at a time -- which is the whole signature, and therefore the
         * ability to forge a SUCCEEDED callback for any payment on the platform.
         */
        boolean matches(String secret, byte[] body) {
            byte[] expected = hmac(
                secret, timestamp + "." + new String(body, StandardCharsets.UTF_8)
            );
            byte[] presented = decodeHex(hex);

            return presented != null && MessageDigest.isEqual(expected, presented);
        }

        private static byte[] hmac(String secret, String signedString) {
            try {
                Mac mac = Mac.getInstance(ALGORITHM);
                mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM
                ));

                return mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException impossible) {
                throw new IllegalStateException(ALGORITHM + " is required by every JVM", impossible);
            }
        }

        /** A signature that is not hex is a failed signature, not a 500. */
        private static byte[] decodeHex(String hex) {
            try {
                return HexFormat.of().parseHex(hex.trim());
            } catch (IllegalArgumentException notHex) {
                return null;
            }
        }
    }
}
