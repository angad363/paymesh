package com.paymesh.shared.idempotency.infrastructure;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.idempotency.application.IdempotencyRepository;
import com.paymesh.shared.idempotency.domain.IdempotencyRecord;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The two properties only a real database can prove, plus the round trip through the real filter
 * chain.
 * <p>
 * Deliberately NOT {@code @Transactional}: the filter's insert has to genuinely commit before the
 * handler runs, and a test transaction would both hide that and make the concurrent threads below
 * invisible to each other.
 * <p>
 * No capability declares an idempotent route yet -- Order (PR3) adds the first two -- so this test
 * registers its own route and its own handler. That is also what proves the registry is the only
 * thing that decides whether a route is covered.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, IdempotencyIntegrationTest.IdempotentTestRoute.class})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    private static final String PATH = "/api/v1/test-orders";
    private static final String ENDPOINT = "POST " + PATH;
    private static final String BODY = "{\"amountMinor\":1000}";

    private final MockMvc mockMvc;
    private final RegisterMerchantService merchants;
    private final IdempotencyRepository records;
    private final CountingHandler handler;

    private String merchantId;

    @Autowired
    IdempotencyIntegrationTest(
        MockMvc mockMvc,
        RegisterMerchantService merchants,
        IdempotencyRepository records,
        CountingHandler handler
    ) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
        this.records = records;
        this.handler = handler;
    }

    @BeforeEach
    void resetHandlerAndRegisterMerchant() {
        handler.reset();
        merchantId = merchants.register(new RegisterMerchantCommand(
            "Tenant Co", "tenant-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId().value();
    }

    /**
     * THE PROPERTY THIS WHOLE LAYER EXISTS FOR. Several simultaneous retries of one key must reach
     * the handler exactly once.
     * <p>
     * This test is written to fail if the insert is ever changed to a read-then-write: with a read
     * first, every thread reads "absent" inside the handler's execution window and every thread
     * proceeds, so the execution count is the number of threads rather than one.
     * <p>
     * The losers are indistinguishable from retries and get either the winner's stored response or
     * 409 REQUEST_IN_PROGRESS, depending on whether the winner had finished. What is NOT allowed is
     * a second execution, or a 2xx that reports a different execution from the winner's.
     */
    @Test
    void runsTheHandlerExactlyOnceForSimultaneousRetriesOfTheSameKey() throws Exception {
        int callers = 4;
        handler.holdFor(Duration.ofMillis(400));
        String key = UUID.randomUUID().toString();

        List<MvcResult> results = inParallel(callers, () -> perform(key, BODY));

        assertThat(handler.executions()).isEqualTo(1);

        List<MvcResult> accepted = results.stream()
            .filter(result -> result.getResponse().getStatus() == 201)
            .toList();

        assertThat(accepted).isNotEmpty();
        assertThat(accepted.stream().map(IdempotencyIntegrationTest::bodyOf).distinct())
            .containsExactly("{\"execution\":1}");

        for (MvcResult result : results) {
            if (result.getResponse().getStatus() != 201) {
                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(bodyOf(result)).contains("REQUEST_IN_PROGRESS");
            }
        }
    }

    /**
     * A record stranded IN_PROGRESS (the process that owned it died) must answer, not block. There
     * is no bounded wait and no lock to queue on -- the 409 is the answer.
     */
    @Test
    void answersImmediatelyWhenAnAttemptIsAlreadyInProgress() {
        String key = UUID.randomUUID().toString();
        records.insertIfAbsent(IdempotencyRecord.started(
            MerchantId.from(merchantId),
            ENDPOINT,
            key,
            IdempotencyRecord.hashOf(BODY.getBytes(StandardCharsets.UTF_8)),
            Instant.now()
        ));

        MvcResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> perform(key, BODY)
        );

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(bodyOf(result)).contains("REQUEST_IN_PROGRESS");
        assertThat(handler.executions()).isZero();
    }

    @Test
    void replaysTheStoredResponseOnASequentialRetry() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult first = perform(key, BODY);
        MvcResult retry = perform(key, BODY);

        assertThat(handler.executions()).isEqualTo(1);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(bodyOf(retry)).isEqualTo(bodyOf(first));
        assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isNull();
        assertThat(retry.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test
    void rejectsTheSameKeyWithADifferentBody() throws Exception {
        String key = UUID.randomUUID().toString();
        perform(key, BODY);

        MvcResult reused = perform(key, "{\"amountMinor\":9999}");

        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(bodyOf(reused)).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(handler.executions()).isEqualTo(1);
    }

    /**
     * A 500 leaves no row behind, so the retry is a real retry. Pinning the key to an answer the
     * server cannot explain would strand the merchant. ADR-009.
     */
    @Test
    void forgetsTheAttemptWhenTheHandlerFails() throws Exception {
        String key = UUID.randomUUID().toString();
        handler.failNext();

        MvcResult failed = perform(key, BODY);

        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        assertThat(records.findBy(MerchantId.from(merchantId), ENDPOINT, key)).isEmpty();

        MvcResult retry = perform(key, BODY);

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(handler.executions()).isEqualTo(2);
    }

    @Test
    void rejectsARequestWithNoIdempotencyKey() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(result)).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(handler.executions()).isZero();
    }

    /** An unregistered route is untouched: no header demanded, no row written. */
    @Test
    void leavesAnUnregisteredRouteAlone() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"passthrough@example.test\"}"))
            .andReturn();

        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"passthrough-two@example.test\"}"))
            .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    // --- helpers --------------------------------------------------------------

    private MvcResult perform(String key, String body) throws Exception {
        return mockMvc.perform(post(PATH)
                .with(callerFor(merchantId))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
    }

    private static <T> List<T> inParallel(int callers, Callable<T> call) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(callers);

        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Future<T>> futures = new ArrayList<>();

            for (int caller = 0; caller < callers; caller++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    return call.call();
                }));
            }

            List<T> results = new ArrayList<>();

            for (Future<T> future : futures) {
                results.add(future.get());
            }

            return results;
        }
    }

    private static String bodyOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RequestPostProcessor callerFor(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }

    /**
     * Registers one idempotent route and a handler that counts its own executions. Overriding the
     * production {@link IdempotentRoutes} bean is the whole registration mechanism, exercised.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdempotentTestRoute {

        @Bean
        @Primary
        IdempotentRoutes testIdempotentRoutes() {
            return new IdempotentRoutes(List.of(ENDPOINT));
        }

        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }
    }

    /**
     * Stands in for {@code POST /api/v1/orders} until it exists. The response names the execution
     * that produced it, so "the handler ran twice" is visible in the response body and not only in
     * a counter.
     */
    @RestController
    static class CountingHandler {

        private final AtomicInteger executions = new AtomicInteger();
        private volatile Duration hold = Duration.ZERO;
        private volatile boolean failNext;

        @PostMapping("/api/v1/test-orders")
        ResponseEntity<String> create(@RequestBody String body) throws InterruptedException {
            int execution = executions.incrementAndGet();

            if (!hold.isZero()) {
                Thread.sleep(hold.toMillis());
            }

            if (failNext) {
                failNext = false;
                return ResponseEntity.status(500).body("{\"code\":\"INTERNAL_ERROR\"}");
            }

            return ResponseEntity.status(201)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"execution\":" + execution + "}");
        }

        void reset() {
            executions.set(0);
            hold = Duration.ZERO;
            failNext = false;
        }

        void holdFor(Duration duration) {
            hold = duration;
        }

        void failNext() {
            failNext = true;
        }

        int executions() {
            return executions.get();
        }
    }
}
