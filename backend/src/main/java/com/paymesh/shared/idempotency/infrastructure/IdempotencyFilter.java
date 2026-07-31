package com.paymesh.shared.idempotency.infrastructure;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.idempotency.application.IdempotencyRepository;
import com.paymesh.shared.idempotency.domain.IdempotencyRecord;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.security.AuthenticatedCallers;
import com.paymesh.shared.security.NoMerchantScopeException;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;

/**
 * Stops a merchant's network retry from creating a second payment.
 * <p>
 * The mechanism is one row and one statement. Before the handler runs, the filter commits an
 * IN_PROGRESS record keyed on {@code merchant + endpoint template + Idempotency-Key}. That commit
 * <em>is</em> the concurrency control: two simultaneous retries race on the primary key, the
 * database picks exactly one winner, and the loser never reaches the handler. Nothing here reads
 * before it writes, and changing it to do so would reintroduce the duplicate it prevents.
 * <p>
 * Registered after Spring Security in the chain, because the scope starts with the authenticated
 * merchant. {@code POST /api/v1/merchants} is public and therefore deliberately not covered.
 * <p>
 * See ADR-009 for why PostgreSQL is the authority and why a 5xx deletes the record.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

    static final String KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final String IN_PROGRESS_MESSAGE =
        "A request with this Idempotency-Key is still being processed. Retry shortly.";

    private final IdempotentRoutes routes;
    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyFilter(
        IdempotentRoutes routes,
        IdempotencyRepository repository,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.routes = routes;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws IOException, ServletException {
        Optional<String> endpoint = routes.templateFor(request);

        if (endpoint.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(KEY_HEADER);

        if (key == null || key.isBlank() || key.length() > IdempotencyRecord.MAX_KEY_LENGTH) {
            writeError(
                response,
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "This endpoint requires an Idempotency-Key header of 1 to "
                    + IdempotencyRecord.MAX_KEY_LENGTH + " characters."
            );
            return;
        }

        Optional<AuthenticatedCaller> caller = AuthenticatedCallers.current();

        if (caller.isEmpty()) {
            // The security chain leaves no idempotent route anonymous, so this is unreachable
            // today. Passing through rather than inventing an error keeps the decision about
            // unauthenticated requests in one place -- SecurityConfiguration.
            chain.doFilter(request, response);
            return;
        }

        MerchantId merchantId;

        try {
            merchantId = caller.get().requireSingleMerchant();
        } catch (NoMerchantScopeException exception) {
            // A filter runs outside every @RestControllerAdvice, so the same answer the controller
            // would have given has to be written here.
            writeError(response, HttpStatus.FORBIDDEN, "NO_MERCHANT_SCOPE", exception.getMessage());
            return;
        }

        CachedBodyRequest cachedRequest = new CachedBodyRequest(request);

        IdempotencyRecord attempt = IdempotencyRecord.started(
            merchantId,
            endpoint.get(),
            key,
            IdempotencyRecord.hashOf(cachedRequest.body()),
            clock.instant()
        );

        if (repository.insertIfAbsent(attempt)) {
            executeAndRemember(cachedRequest, response, chain, attempt);
        } else {
            replayOrReject(response, attempt);
        }
    }

    private void executeAndRemember(
        CachedBodyRequest request,
        HttpServletResponse response,
        FilterChain chain,
        IdempotencyRecord attempt
    ) throws IOException, ServletException {
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        int status;
        byte[] body;

        try {
            chain.doFilter(request, captured);
            status = captured.getStatus();
            body = captured.getContentAsByteArray();
            captured.copyBodyToResponse();
        } catch (Throwable failure) {
            forget(attempt);
            throw failure;
        }

        if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            // The server does not know what it did. Pinning the key to this answer would make a
            // legitimate retry impossible, so the attempt is forgotten and the error is returned
            // exactly as produced. ADR-009.
            forget(attempt);
            return;
        }

        repository.complete(
            attempt.completedWith(status, new String(body, StandardCharsets.UTF_8), clock.instant())
        );
    }

    private void replayOrReject(HttpServletResponse response, IdempotencyRecord attempt)
        throws IOException {
        Optional<IdempotencyRecord> stored = repository.findBy(
            attempt.merchantId(), attempt.endpoint(), attempt.idempotencyKey()
        );

        if (stored.isEmpty()) {
            // The row existed a moment ago and is gone: a concurrent attempt failed with a 5xx and
            // deleted it. "Try again" is the honest answer, and it is the one a retry can act on.
            writeError(response, HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", IN_PROGRESS_MESSAGE);
            return;
        }

        IdempotencyRecord record = stored.get();

        if (!record.requestHash().equals(attempt.requestHash())) {
            writeError(
                response,
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request body."
            );
            return;
        }

        if (!record.isCompleted()) {
            writeError(response, HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", IN_PROGRESS_MESSAGE);
            return;
        }

        response.setStatus(record.responseStatus());
        // Only the status and the body are stored. Every response this API produces is JSON, so
        // the content type is a constant rather than a fourth column to keep in step.
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(REPLAYED_HEADER, "true");

        if (record.responseBody() != null) {
            response.getOutputStream().write(record.responseBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void forget(IdempotencyRecord attempt) {
        repository.delete(attempt.merchantId(), attempt.endpoint(), attempt.idempotencyKey());
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
        throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(code, message));
    }

    /**
     * Holds the body in memory so it can be hashed and still be read by the handler.
     * <p>
     * A servlet body is a one-shot stream: hashing it without this wrapper would hand the
     * controller an empty request.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffered = new ByteArrayInputStream(body);

            return new ServletInputStream() {

                @Override
                public int read() {
                    return buffered.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return buffered.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return buffered.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Idempotent routes are read synchronously");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();

            return new BufferedReader(new InputStreamReader(
                getInputStream(),
                encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)
            ));
        }
    }
}
