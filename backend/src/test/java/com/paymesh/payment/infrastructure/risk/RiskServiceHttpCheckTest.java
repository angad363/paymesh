package com.paymesh.payment.infrastructure.risk;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.paymesh.payment.application.RiskCheck.Decision;
import com.paymesh.payment.application.RiskUnavailableException;
import com.paymesh.shared.tenant.MerchantId;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RiskServiceHttpCheck}: the synchronous call to the extracted risk service, and what
 * happens when it cannot be reached at all (ADR-044).
 * <p>
 * A short read timeout (200ms) and a WireMock delay well past it prove PAYMENT DOES NOT HANG on an
 * unreachable risk service -- the point {@code assertTimeout} would only prove weakly, since a bug
 * that made the call synchronous-forever would still "pass" a generous timeout. Here the timeout is
 * the mechanism under test, not a test harness safety net.
 */
class RiskServiceHttpCheckTest {

    private static final long BLOCK_THRESHOLD = 50_000L;
    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockServer wireMock;
    private RecordingFallbackRecorder recorder;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        recorder = new RecordingFallbackRecorder();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void aCleanDecisionFromRiskPassesThroughWithoutTouchingTheFallback() {
        wireMock.stubFor(post(urlEqualTo("/internal/v1/risk-evaluations")).willReturn(
            aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"permitted\":true,\"assessmentId\":\"rsk_1\"}")
        ));

        Decision decision = check(1_00L).evaluate(
            MERCHANT, "pi_1", 1_00L, "INR", "cus_1", "device-1"
        );

        assertThat(decision.permitted()).isTrue();
        assertThat(decision.assessmentId()).isEqualTo("rsk_1");
        assertThat(recorder.calls).as("Risk answered; the fallback never runs").isEmpty();
    }

    @Test
    void belowThresholdUnreachableReturnsPermittedAndRecordsTheAllow() {
        wireMock.stop(); // nothing listens on this port any more: connection refused

        Decision decision = check(BLOCK_THRESHOLD - 1).evaluate(
            MERCHANT, "pi_2", BLOCK_THRESHOLD - 1, "INR", "cus_1", null
        );

        assertThat(decision.permitted()).isTrue();
        assertThat(decision.assessmentId()).isNull();
        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0)).isEqualTo("ALLOW");
    }

    @Test
    void atOrAboveThresholdUnreachableThrowsAndRecordsTheBlock() {
        wireMock.stop();

        RiskServiceHttpCheck check = check(BLOCK_THRESHOLD);

        assertThatThrownBy(() ->
            check.evaluate(MERCHANT, "pi_3", BLOCK_THRESHOLD, "INR", "cus_1", null)
        ).isInstanceOf(RiskUnavailableException.class);

        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0)).isEqualTo("BLOCK");
    }

    /**
     * THE TIMEOUT, NOT A HANG. The stub answers, but only after 2 seconds -- ten times this
     * check's own 200ms read timeout -- so a passing test here is proof the call gave up rather
     * than proof nothing was listening.
     */
    @Test
    void aSlowRiskServiceTimesOutAndFallsBackRatherThanHanging() {
        wireMock.stubFor(post(urlEqualTo("/internal/v1/risk-evaluations")).willReturn(
            aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"permitted\":true,\"assessmentId\":\"rsk_2\"}")
                .withFixedDelay(2_000)
        ));

        long start = System.nanoTime();

        Decision decision = check(BLOCK_THRESHOLD - 1).evaluate(
            MERCHANT, "pi_4", BLOCK_THRESHOLD - 1, "INR", "cus_1", null
        );

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(decision.permitted())
            .as("below the block threshold, a timeout still allows")
            .isTrue();
        assertThat(elapsed)
            .as("the call returned near the 200ms read timeout, not after the 2s delay")
            .isLessThan(Duration.ofSeconds(1));
        assertThat(recorder.calls).hasSize(1);
    }

    @Test
    void aClientErrorFromRiskFailsLoudRatherThanFallingOpen() {
        // 401: the key did not match. A misconfiguration, not an outage -- must not silently
        // fail-open a sub-threshold payment the way a real outage does.
        wireMock.stubFor(post(urlEqualTo("/internal/v1/risk-evaluations"))
            .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() ->
            check(BLOCK_THRESHOLD - 1).evaluate(MERCHANT, "pi_5", BLOCK_THRESHOLD - 1, "INR", "cus_1", null)
        ).isInstanceOf(RiskServiceProtocolException.class);

        assertThat(recorder.calls).as("a 4xx never reaches the by-tier fallback").isEmpty();
    }

    @Test
    void aMalformedSuccessBodyFailsLoudRatherThanFallingOpen() {
        // A 2xx with no verdict is a contract violation; it must fail loud, not NPE and be
        // mis-reported as an outage that fail-opens.
        wireMock.stubFor(post(urlEqualTo("/internal/v1/risk-evaluations")).willReturn(
            aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")
        ));

        assertThatThrownBy(() ->
            check(BLOCK_THRESHOLD - 1).evaluate(MERCHANT, "pi_6", BLOCK_THRESHOLD - 1, "INR", "cus_1", null)
        ).isInstanceOf(RiskServiceProtocolException.class);

        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void aServerErrorFromRiskIsTreatedAsUnavailableAndFallsBackByTier() {
        wireMock.stubFor(post(urlEqualTo("/internal/v1/risk-evaluations"))
            .willReturn(aResponse().withStatus(503)));

        Decision decision = check(BLOCK_THRESHOLD - 1).evaluate(
            MERCHANT, "pi_7", BLOCK_THRESHOLD - 1, "INR", "cus_1", null
        );

        assertThat(decision.permitted()).isTrue();
        assertThat(recorder.calls).containsExactly("ALLOW");
    }

    /**
     * THE FAST-FAIL PATH IS LIVE (ADR-044). With {@code minimumNumberOfCalls} wrongly left at
     * resilience4j's default of 100, a window of 4 could never open the breaker and this test would
     * hang on the fallback path forever; tying the minimum to the window is what makes the breaker
     * reach OPEN, after which {@code CallNotPermittedException} fast-fails without spending a
     * timeout.
     */
    @Test
    void theBreakerOpensAfterRepeatedFailuresSoConfirmsStopPayingTheTimeout() {
        wireMock.stop(); // every call now fails: connection refused

        CircuitBreaker breaker = CircuitBreaker.of("breaker-opens-test", CircuitBreakerConfig.custom()
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(200);
        requestFactory.setReadTimeout(200);

        RiskServiceHttpCheck check = new RiskServiceHttpCheck(
            RestClient.builder().requestFactory(requestFactory).build(),
            "http://localhost:1/internal/v1/risk-evaluations",
            "irrelevant",
            breaker,
            new RiskUnavailablePolicy(BLOCK_THRESHOLD, recorder),
            JSON
        );

        for (int i = 0; i < 4; i++) {
            check.evaluate(MERCHANT, "pi_b" + i, 1_00L, "INR", "cus_1", null);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // One more call: the breaker is open, so this is CallNotPermittedException -> fallback,
        // not another timeout. The fallback still runs (and records), proving the path is wired.
        Decision decision = check.evaluate(MERCHANT, "pi_b_open", 1_00L, "INR", "cus_1", null);

        assertThat(decision.permitted()).isTrue();
        assertThat(recorder.calls).as("four timeouts plus one fast-fail all recorded").hasSize(5);
    }

    private RiskServiceHttpCheck check(long testAmountMinor) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(200);
        requestFactory.setReadTimeout(200);

        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
            // A high failure threshold and a large sliding window: this test drives the breaker
            // OPEN by amount and timeout behaviour, not by tripping the breaker itself -- each test
            // method gets a fresh breaker so one test's failures never bleed into another's.
            .slidingWindowSize(10)
            .failureRateThreshold(90)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build();

        return new RiskServiceHttpCheck(
            RestClient.builder().requestFactory(requestFactory).build(),
            wireMock.isRunning()
                ? wireMock.baseUrl() + "/internal/v1/risk-evaluations"
                : "http://localhost:1/internal/v1/risk-evaluations",
            "irrelevant-in-this-test",
            CircuitBreaker.of("test-risk-evaluation-" + testAmountMinor, breakerConfig),
            new RiskUnavailablePolicy(BLOCK_THRESHOLD, recorder),
            JSON
        );
    }

    private static final class RecordingFallbackRecorder implements RiskFallbackRecorder {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void record(String paymentIntentId, long amountMinor, String decision, String reason) {
            calls.add(decision);
        }
    }
}
