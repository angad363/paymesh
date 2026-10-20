package com.paymesh.ledger.infrastructure.security;

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
 * THE AUTHENTICATION FOR {@code /internal/v1/ledger/**} (ADR-047 section 1a): a shared key, constant
 * time compared, the shape {@code RiskEvaluationKeyFilter} proved. Its own property
 * ({@code paymesh.ledger.read-key}) so a leak of it reads a merchant's available contributions and
 * nothing else. A BLANK key matches nothing -- the route fails closed rather than open.
 */
public final class LedgerReadKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-PayMesh-Ledger-Key";

    static final String PATH_PREFIX = "/internal/v1/ledger";

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public LedgerReadKeyFilter(String apiKey, ObjectMapper objectMapper) {
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
                "LEDGER_KEY_INVALID",
                "A valid ledger read key is required."
            ));

            return;
        }

        chain.doFilter(request, response);
    }

    private boolean matches(String presented) {
        if (presented == null || apiKey == null || apiKey.isBlank()) {
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
