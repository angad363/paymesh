package com.paymesh.settlement.infrastructure.provider;

import com.paymesh.settlement.application.PayoutGateway;
import com.paymesh.settlement.application.PayoutSubmissionFailedException;
import com.paymesh.settlement.domain.Payout;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Submits a payout to the provider over HTTP.
 *
 * <h2>THE PROVIDER IS A THIRD PARTY, EVEN WHEN IT IS THE SIMULATOR IN THIS PROCESS</h2>
 *
 * Nothing here imports {@code com.paymesh.simulator}. The simulator publishes a contract -- a URL,
 * a key header, a JSON body -- and this speaks it, exactly as the payment path does. That is what
 * keeps {@code ModuleBoundaryTest}'s empty allowlist on the simulator honest, and it is why the two
 * can be deployed apart.
 *
 * <h2>PayMesh's own payout id is the idempotency key</h2>
 *
 * It travels as {@code externalReference} and the provider makes it unique
 * ({@code uq_provider_payouts_external_reference}). So a submission that lands and whose response
 * is lost can be retried without moving money twice -- which is the entire reason the submission
 * loop is allowed to retry at all.
 */
public final class HttpPayoutGateway implements PayoutGateway {

    private static final String API_KEY_HEADER = "X-PayMesh-Simulator-Key";

    private final RestClient restClient;
    private final String payoutUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public HttpPayoutGateway(
        RestClient restClient, String payoutUrl, String apiKey, ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.payoutUrl = payoutUrl;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public String submit(Payout payout) {
        Map<String, Object> body = Map.of(
            "externalReference", payout.payoutId().value(),
            "destination", payout.destination(),
            "amountMinor", payout.amountMinor(),
            "currency", payout.currency()
        );

        try {
            return restClient.post()
                .uri(payoutUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, apiKey)
                .body(body)
                // Conversion off: a non-2xx is DATA here, not an exception, so a refusal and a
                // transport failure take the same path and both spend one attempt of the budget.
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new PayoutSubmissionFailedException(
                            "Provider refused payout with status " + response.getStatusCode().value()
                        );
                    }

                    return providerReferenceIn(response.bodyTo(String.class));
                }, false);
        } catch (PayoutSubmissionFailedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PayoutSubmissionFailedException(
                "Could not reach the payout provider: " + failure.getMessage(), failure
            );
        }
    }

    private String providerReferenceIn(String body) {
        if (body == null || body.isBlank()) {
            throw new PayoutSubmissionFailedException("Provider accepted the payout with no body");
        }

        JsonNode reference = objectMapper.readTree(body).get("providerPayoutId");

        if (reference == null || reference.asString().isBlank()) {
            throw new PayoutSubmissionFailedException(
                "Provider accepted the payout without naming it"
            );
        }

        return reference.asString();
    }
}
