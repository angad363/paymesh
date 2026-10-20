package com.paymesh.payment.infrastructure.risk;

import com.paymesh.payment.application.RiskCheck;
import com.paymesh.shared.tenant.MerchantId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payment's {@link RiskCheck}, answered by the risk SERVICE, over HTTP (ADR-044) -- the
 * synchronous replacement for {@link RiskModuleCheck}'s in-process call, active when {@code
 * paymesh.risk.mode=http}.
 *
 * <h2>THE SAME SHAPE {@code HttpPayoutGateway} ALREADY PROVED</h2>
 *
 * A dedicated {@code RestClient} with short, explicit timeouts, a shared-key header, and
 * {@code exchange} with conversion off so a non-2xx answer is data rather than an exception --
 * the difference is what happens after: a clean 2xx/4xx decision from Risk is parsed and returned
 * as-is; a TIMEOUT, a CONNECTION FAILURE, or the breaker already OPEN is the one case this class
 * exists to add, and it is handed to {@link RiskUnavailablePolicy} rather than propagated.
 *
 * <h2>THE CIRCUIT BREAKER GUARDS THE HTTP CALL, NOT THE FALLBACK</h2>
 *
 * {@link CircuitBreaker#executeSupplier} wraps only the network round trip. Once the breaker is
 * open, every subsequent call fails fast with {@link CallNotPermittedException} instead of
 * spending its own timeout budget waiting on a service already known to be down -- that fast
 * failure is what keeps a sustained risk outage from stacking up hung confirm threads.
 */
public final class RiskServiceHttpCheck implements RiskCheck {

    private static final String API_KEY_HEADER = "X-PayMesh-Risk-Key";

    private final RestClient restClient;
    private final String evaluateUrl;
    private final String evaluateKey;
    private final CircuitBreaker breaker;
    private final RiskUnavailablePolicy fallback;
    private final ObjectMapper objectMapper;

    public RiskServiceHttpCheck(
        RestClient restClient,
        String evaluateUrl,
        String evaluateKey,
        CircuitBreaker breaker,
        RiskUnavailablePolicy fallback,
        ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.evaluateUrl = evaluateUrl;
        this.evaluateKey = evaluateKey;
        this.breaker = breaker;
        this.fallback = fallback;
        this.objectMapper = objectMapper;
    }

    @Override
    public Decision evaluate(
        MerchantId merchantId,
        String paymentIntentId,
        long amountMinor,
        String currency,
        String customerId,
        String device
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId.value());
        body.put("paymentIntentId", paymentIntentId);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        body.put("customerId", customerId);
        body.put("device", device);

        try {
            return breaker.executeSupplier(() -> httpPost(body));
        } catch (CallNotPermittedException breakerOpen) {
            return fallback.apply(paymentIntentId, amountMinor, "circuit breaker open");
        } catch (RiskServiceProtocolException protocolFailure) {
            // NOT an outage: a 4xx or a malformed body is our-side/contract, must fail loud rather
            // than fail open. Rethrow -- it is ignored by the breaker and ends the confirm as a 500.
            throw protocolFailure;
        } catch (RuntimeException unavailable) {
            // A timeout, a connection refusal, or a 5xx from Risk: genuinely could not get a
            // decision, so the by-tier policy decides. The message carries which of those it was.
            return fallback.apply(
                paymentIntentId, amountMinor, "risk unavailable: " + unavailable.getMessage()
            );
        }
    }

    /**
     * Conversion off, the {@code HttpPayoutGateway} shape -- but the meaning of a non-2xx is the
     * opposite here, and that difference drives the three-way split below. For a payout a non-2xx
     * is the provider's business answer ("refused"); for risk the verdict is always in a 2xx body
     * ({@code RiskEvaluationController} returns 200 even for a block), so a non-2xx means something
     * went wrong, and WHICH thing decides what we do:
     *
     * <ul>
     *   <li><b>4xx</b> -- our request was bad or the key was rejected: a misconfiguration or bug,
     *       not an outage. {@link RiskServiceProtocolException}, which propagates and fails the
     *       confirm loudly rather than silently fail-opening.</li>
     *   <li><b>5xx</b> -- Risk is erroring: treated as unavailable, so it counts as a breaker
     *       failure and is handed to the by-tier fallback exactly like a timeout.</li>
     *   <li><b>2xx with no {@code permitted} field</b> -- a contract violation, handled like a 4xx:
     *       fail loud, not a NPE mis-reported as a transport failure.</li>
     * </ul>
     */
    private Decision httpPost(Map<String, Object> body) {
        return restClient.post()
            .uri(evaluateUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .header(API_KEY_HEADER, evaluateKey)
            .body(body)
            .exchange((request, response) -> {
                int status = response.getStatusCode().value();

                if (response.getStatusCode().is4xxClientError()) {
                    // Our request was rejected (400) or the key did not match (401/403): a
                    // misconfiguration or a bug, not an outage. Fail loud, never fail open.
                    throw new RiskServiceProtocolException(
                        "Risk service rejected the evaluation request with status " + status
                    );
                }

                if (!response.getStatusCode().is2xxSuccessful()) {
                    // 5xx: Risk is erroring. Treated as unavailable -- counts as a breaker failure
                    // and is handed to the by-tier fallback, exactly like a timeout.
                    throw new IllegalStateException(
                        "Risk service errored on the evaluation with status " + status
                    );
                }

                JsonNode json = objectMapper.readTree(response.bodyTo(String.class));

                if (!json.hasNonNull("permitted")) {
                    // A 2xx with no verdict is a contract violation, not a decision and not an
                    // outage. Fail loud rather than NPE and be mis-reported as unavailable.
                    throw new RiskServiceProtocolException(
                        "Risk service returned a 2xx response with no 'permitted' field"
                    );
                }

                return new Decision(
                    json.get("permitted").asBoolean(),
                    json.hasNonNull("assessmentId") ? json.get("assessmentId").asString() : null
                );
            }, false);
    }
}
