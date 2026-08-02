package com.paymesh.simulator.infrastructure.security;

import com.paymesh.shared.api.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * THE AUTHENTICATION FOR {@code /sim/v1/**}. There is no other.
 *
 * <h2>Why this route needs one at all</h2>
 *
 * {@code POST /sim/v1/payments} queues a callback that will mark a PayMesh payment SUCCEEDED. An
 * unauthenticated simulator is therefore an unauthenticated way to collect any payment on the
 * platform -- the exact compromise {@code ProviderCallbackSignatureFilter} exists to prevent,
 * reintroduced one route over.
 *
 * <h2>Why not a merchant bearer token</h2>
 *
 * For the same reason. A merchant able to drive the provider could authorize its own collection.
 * {@code /sim/v1/**} is not the merchant API, it appears in no merchant documentation, and it is
 * {@code permitAll()} on the Spring chain precisely so that no bearer token is ever a way in.
 *
 * <h2>Why not the callback secret</h2>
 *
 * Reusing {@code paymesh.provider.callback-secret} would work and would be less code. It was
 * rejected: one value would then both mint provider payments and sign the callbacks marking them
 * collected, so a single leak does both jobs -- and it makes per-provider secrets (open item 8)
 * harder rather than easier, because the one secret would have two unrelated meanings and could not
 * be split without changing both directions at once.
 *
 * <h2>Why a static key here and an HMAC over the body there</h2>
 *
 * The two directions are not symmetrical. Inbound, the body IS the money-moving claim, it arrives
 * from outside the trust boundary, and a captured request can be replayed -- so the body and a
 * timestamp have to be inside the signed string. Outbound, the money-moving claim is the callback
 * this module later emits, and that one is HMAC-signed. A static key on a request that merely asks
 * the provider to start a payment is proportionate. It is weaker, and it is weaker on purpose and on
 * the record (ADR-017) rather than by omission.
 * <p>
 * Comparison is constant time and the failure is one answer with no detail, on the same terms as the
 * callback filter: which check failed tells an attacker whether they hold the key.
 */
public final class SimulatorApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-PayMesh-Simulator-Key";

    static final String PATH_PREFIX = "/sim/v1";

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public SimulatorApiKeyFilter(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    /** Everything else authenticates the normal way and must not touch this. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathWithinApplication(request).startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws IOException, ServletException {
        if (!matches(request.getHeader(API_KEY_HEADER))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
                "SIMULATOR_KEY_INVALID",
                "A valid simulator key is required."
            ));

            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * CONSTANT TIME, via {@link MessageDigest#isEqual}. A {@code String.equals} returns as soon as
     * two bytes differ, and the timing difference is enough to recover the key one byte at a time --
     * which is the ability to make the provider authorize any payment.
     */
    private boolean matches(String presented) {
        if (presented == null) {
            return false;
        }

        return MessageDigest.isEqual(
            apiKey.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();

        return contextPath == null || contextPath.isEmpty()
            ? uri
            : uri.substring(contextPath.length());
    }
}
