package com.paymesh.shared.idempotency.infrastructure;

import com.paymesh.shared.idempotency.application.IdempotencyRepository;
import com.paymesh.shared.idempotency.domain.IdempotencyRecord;
import com.paymesh.shared.idempotency.domain.IdempotencyStatus;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every row of the outcome table in the idempotency design, proved without a database or a Spring
 * context. The repository is a map; what is under test is the filter's decision-making.
 */
class IdempotencyFilterTest {

    private static final String ENDPOINT = "POST /api/v1/orders";
    private static final String KEY = "1f2b0e2c-0f1a-4a1a-9a1a-0f1a4a1a9a1a";
    private static final Instant NOW = Instant.parse("2026-07-31T10:15:30Z");

    private final MerchantId merchantId = MerchantId.generate();
    private final StubIdempotencyRepository repository = new StubIdempotencyRepository();
    private final AtomicInteger handlerExecutions = new AtomicInteger();

    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter(
            new IdempotentRoutes(List.of(ENDPOINT)),
            repository,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        authenticateFor(merchantId);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- pass-through ---------------------------------------------------------

    /**
     * Nothing is idempotent by default. A route that has not been registered must reach its handler
     * with no header requirement, no hashing and no row -- otherwise adding this filter would break
     * every endpoint that already exists.
     */
    @Test
    void passesThroughARouteThatIsNotRegistered() throws Exception {
        MockHttpServletResponse response = invoke(
            request("POST", "/api/v1/customers", "{\"email\":\"a@b.test\"}", null),
            respondWith(201, "{\"id\":\"cus_1\"}")
        );

        assertThat(handlerExecutions).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(repository.records).isEmpty();
    }

    /** Same path, different method: the method is part of the route, not decoration. */
    @Test
    void passesThroughARegisteredPathUnderAnUnregisteredMethod() throws Exception {
        MockHttpServletResponse response = invoke(
            request("GET", "/api/v1/orders", "", null),
            respondWith(200, "[]")
        );

        assertThat(handlerExecutions).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(repository.records).isEmpty();
    }

    /**
     * The stored endpoint is the TEMPLATE, not the concrete URI. Storing
     * {@code /api/v1/orders/ord_abc/cancel} would scope the key per-order, so the same key aimed at
     * two orders would be two independent scopes instead of one reused key.
     */
    @Test
    void storesThePathTemplateRatherThanTheConcreteUri() throws Exception {
        String template = "POST /api/v1/orders/{orderId}/cancel";
        filter = new IdempotencyFilter(
            new IdempotentRoutes(List.of(template)),
            repository,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        invoke(
            request("POST", "/api/v1/orders/ord_11111111-1111-4111-8111-111111111111/cancel", "{}", KEY),
            respondWith(200, "{}")
        );

        assertThat(repository.only().endpoint()).isEqualTo(template);
    }

    // --- the header itself ----------------------------------------------------

    @Test
    void rejectsAMissingIdempotencyKey() throws Exception {
        MockHttpServletResponse response = invoke(orderRequest("{}", null), respondWith(201, "{}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(handlerExecutions).hasValue(0);
    }

    @Test
    void rejectsABlankIdempotencyKey() throws Exception {
        MockHttpServletResponse response = invoke(orderRequest("{}", "   "), respondWith(201, "{}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    /** The key is a primary-key column; a key longer than it must be refused, never truncated. */
    @Test
    void rejectsAnOverlongIdempotencyKey() throws Exception {
        MockHttpServletResponse response = invoke(
            orderRequest("{}", "k".repeat(256)),
            respondWith(201, "{}")
        );

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(repository.records).isEmpty();
    }

    /** A key with no tenant has no scope. The handler must not run on a guess. */
    @Test
    void rejectsACallerWithNoMerchantScope() throws Exception {
        SecurityContextHolder.clearContext();
        authenticateWithRoles(List.of());

        MockHttpServletResponse response = invoke(orderRequest("{}", KEY), respondWith(201, "{}"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("NO_MERCHANT_SCOPE");
        assertThat(handlerExecutions).hasValue(0);
    }

    // --- outcome 1: the insert wins -------------------------------------------

    @Test
    void runsTheHandlerAndStoresTheResponse() throws Exception {
        MockHttpServletResponse response = invoke(
            orderRequest("{\"amountMinor\":100}", KEY),
            respondWith(201, "{\"id\":\"ord_1\"}")
        );

        assertThat(handlerExecutions).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"ord_1\"}");
        assertThat(response.getHeader("Idempotency-Replayed")).isNull();

        IdempotencyRecord stored = repository.only();
        assertThat(stored.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(stored.responseStatus()).isEqualTo(201);
        assertThat(stored.responseBody()).isEqualTo("{\"id\":\"ord_1\"}");
        assertThat(stored.completedAt()).isEqualTo(NOW);
        assertThat(stored.endpoint()).isEqualTo(ENDPOINT);
        assertThat(stored.merchantId()).isEqualTo(merchantId);
    }

    /** Hashing the body must not consume it. */
    @Test
    void letsTheHandlerReadTheRequestBody() throws Exception {
        MockHttpServletResponse response = invoke(
            orderRequest("{\"amountMinor\":100}", KEY),
            (request, servletResponse) -> {
                handlerExecutions.incrementAndGet();
                String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                writeBody((HttpServletResponse) servletResponse, 201, body);
            }
        );

        assertThat(response.getContentAsString()).isEqualTo("{\"amountMinor\":100}");
    }

    /**
     * A 4xx is a decided answer: the request was understood and rejected, and repeating it deserves
     * the same rejection rather than a second trip through the handler.
     */
    @Test
    void storesAClientError() throws Exception {
        invoke(orderRequest("{}", KEY), respondWith(422, "{\"code\":\"CUSTOMER_NOT_FOUND\"}"));

        assertThat(repository.only().responseStatus()).isEqualTo(422);
    }

    /**
     * A 500 means the server does not know what it did. Keeping the row would pin the key to a
     * failure and make a legitimate retry impossible, so the row goes and the error is returned
     * exactly as the handler produced it. ADR-009.
     */
    @Test
    void deletesTheRecordWhenTheHandlerReturnsAServerError() throws Exception {
        MockHttpServletResponse response = invoke(
            orderRequest("{}", KEY),
            respondWith(500, "{\"code\":\"INTERNAL_ERROR\"}")
        );

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"INTERNAL_ERROR\"}");
        assertThat(repository.records).isEmpty();
    }

    @Test
    void deletesTheRecordWhenTheHandlerThrows() {
        assertThatThrownBy(() -> invoke(orderRequest("{}", KEY), (request, response) -> {
            handlerExecutions.incrementAndGet();
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(repository.records).isEmpty();
    }

    // --- outcome 2 and 3: the insert loses ------------------------------------

    @Test
    void rejectsTheSameKeyCarryingADifferentBody() throws Exception {
        repository.put(completedRecord("{\"amountMinor\":100}", 201, "{\"id\":\"ord_1\"}"));

        MockHttpServletResponse response = invoke(
            orderRequest("{\"amountMinor\":999}", KEY),
            respondWith(201, "{\"id\":\"ord_2\"}")
        );

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(handlerExecutions).hasValue(0);
    }

    @Test
    void replaysAStoredResponseVerbatim() throws Exception {
        repository.put(completedRecord("{\"amountMinor\":100}", 201, "{\"id\":\"ord_1\"}"));

        MockHttpServletResponse response = invoke(
            orderRequest("{\"amountMinor\":100}", KEY),
            respondWith(201, "{\"id\":\"ord_2\"}")
        );

        assertThat(handlerExecutions).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"ord_1\"}");
        assertThat(response.getHeader("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test
    void rejectsARetryWhileTheFirstAttemptIsStillInProgress() throws Exception {
        repository.put(IdempotencyRecord.started(
            merchantId, ENDPOINT, KEY, IdempotencyRecord.hashOf("{}".getBytes(StandardCharsets.UTF_8)), NOW
        ));

        MockHttpServletResponse response = invoke(orderRequest("{}", KEY), respondWith(201, "{}"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("REQUEST_IN_PROGRESS");
        assertThat(handlerExecutions).hasValue(0);
    }

    /**
     * The scope is merchant + endpoint + key. Another merchant reusing a key it cannot see must not
     * be handed the first merchant's response -- that would be a cross-tenant read through a header.
     */
    @Test
    void scopesTheKeyToTheMerchant() throws Exception {
        repository.put(completedRecord("{}", 201, "{\"id\":\"ord_1\"}"));

        SecurityContextHolder.clearContext();
        authenticateFor(MerchantId.generate());

        MockHttpServletResponse response = invoke(
            orderRequest("{}", KEY),
            respondWith(201, "{\"id\":\"ord_other\"}")
        );

        assertThat(handlerExecutions).hasValue(1);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"ord_other\"}");
    }

    // --- helpers --------------------------------------------------------------

    private IdempotencyRecord completedRecord(String body, int status, String responseBody) {
        return IdempotencyRecord.started(
            merchantId,
            ENDPOINT,
            KEY,
            IdempotencyRecord.hashOf(body.getBytes(StandardCharsets.UTF_8)),
            NOW
        ).completedWith(status, responseBody, NOW);
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request, FilterChain chain)
        throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private FilterChain respondWith(int status, String body) {
        return (request, response) -> {
            handlerExecutions.incrementAndGet();
            writeBody((HttpServletResponse) response, status, body);
        };
    }

    private static void writeBody(HttpServletResponse response, int status, String body)
        throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest orderRequest(String body, String key) {
        return request("POST", "/api/v1/orders", body, key);
    }

    private static MockHttpServletRequest request(String method, String uri, String body, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));

        if (key != null) {
            request.addHeader("Idempotency-Key", key);
        }

        return request;
    }

    private static String errorCode(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        return new ObjectMapper().readTree(response.getContentAsString()).get("code").asText();
    }

    private static void authenticateFor(MerchantId merchantId) {
        authenticateWithRoles(List.of("MERCHANT_ADMIN:" + merchantId.value()));
    }

    private static void authenticateWithRoles(List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", roles)
            .build();

        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken(jwt, null));
    }

    /** Map-backed stand-in for the real table; insertIfAbsent is the primary key. */
    private static final class StubIdempotencyRepository implements IdempotencyRepository {

        private final Map<String, IdempotencyRecord> records = new HashMap<>();

        @Override
        public boolean insertIfAbsent(IdempotencyRecord record) {
            return records.putIfAbsent(keyOf(record), record) == null;
        }

        @Override
        public Optional<IdempotencyRecord> findBy(
            MerchantId merchantId,
            String endpoint,
            String idempotencyKey
        ) {
            return Optional.ofNullable(records.get(keyOf(merchantId, endpoint, idempotencyKey)));
        }

        @Override
        public void complete(IdempotencyRecord record) {
            records.put(keyOf(record), record);
        }

        @Override
        public void delete(MerchantId merchantId, String endpoint, String idempotencyKey) {
            records.remove(keyOf(merchantId, endpoint, idempotencyKey));
        }

        void put(IdempotencyRecord record) {
            records.put(keyOf(record), record);
        }

        IdempotencyRecord only() {
            assertThat(records).hasSize(1);
            return records.values().iterator().next();
        }

        private static String keyOf(IdempotencyRecord record) {
            return keyOf(record.merchantId(), record.endpoint(), record.idempotencyKey());
        }

        private static String keyOf(MerchantId merchantId, String endpoint, String idempotencyKey) {
            return merchantId.value() + "|" + endpoint + "|" + idempotencyKey;
        }
    }
}
