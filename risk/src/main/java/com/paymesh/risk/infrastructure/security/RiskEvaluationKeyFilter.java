package com.paymesh.risk.infrastructure.security;

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
 * THE AUTHENTICATION FOR {@code /internal/v1/risk-evaluations}. There is no other (ADR-044
 * section 3).
 *
 * <h2>WHY A SHARED KEY, AND NOT JWT</h2>
 *
 * Every other extracted deployable (webhook, engagement) carries the JWT half of the security
 * boundary because a merchant's bearer token reaches them. This route is never reached by a
 * merchant or a browser -- its only caller is Payment's confirm path, machine to machine, on the
 * money path's synchronous critical section. Bringing in a full JWT stack for one caller that
 * will never hold a merchant token would be dead weight; this is instead the same minimal shape
 * {@code SimulatorApiKeyFilter} already proved for exactly this situation (ADR-017/ADR-041): a
 * plain filter, constant-time compared, no Spring Security dependency on the classpath at all.
 *
 * <h2>WHY NOT REUSE ANOTHER SERVICE'S SHARED KEY</h2>
 *
 * Same reasoning {@code SimulatorApiKeyFilter}'s javadoc gives for not reusing the provider
 * callback secret: one value minting a risk decision and one value meaning something else would
 * let a single leak do two unrelated jobs. {@code paymesh.risk.evaluate-key} exists for this one
 * purpose only.
 *
 * <p>Comparison is constant time and the failure is one answer with no detail: which check failed
 * tells an attacker whether they hold the key.
 */
public final class RiskEvaluationKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-PayMesh-Risk-Key";

    static final String PATH_PREFIX = "/internal/v1/risk-evaluations";

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public RiskEvaluationKeyFilter(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    /** Everything else (actuator) is unauthenticated by omission and must not touch this. */
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
                "RISK_KEY_INVALID",
                "A valid risk evaluation key is required."
            ));

            return;
        }

        chain.doFilter(request, response);
    }

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
