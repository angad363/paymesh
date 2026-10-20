package com.paymesh.settlement.infrastructure.ledger;

import com.paymesh.settlement.application.AvailableFunds;
import com.paymesh.settlement.application.LedgerReadUnavailableException;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Settlement's {@link AvailableFunds}, answered by the ledger over HTTP (ADR-047 section 1a): the
 * synchronous half of the extraction, beside the monolith's in-process {@code
 * LedgerModuleAvailableFunds}. Read-only -- Settlement still cannot post; journals stay event-driven.
 *
 * <h2>FAILS CLOSED (ADR-047 section 5)</h2>
 *
 * Every failure -- timeout, refusal, any non-2xx, a body missing its list -- is a {@link
 * LedgerReadUnavailableException}, never an empty list. An empty list would read as "this merchant
 * has nothing available" and the cut would quietly do nothing, but a PARTIAL or guessed list would
 * cut a mis-sized payout; the one thing that must not happen is a batch cut on a read not got.
 * The sweeper turns the exception into a recorded skip and the next tick retries.
 */
public final class LedgerHttpAvailableFunds implements AvailableFunds {

    static final String KEY_HEADER = "X-PayMesh-Ledger-Key";

    private final RestClient restClient;
    private final String baseUrl;
    private final String key;
    private final ObjectMapper objectMapper;

    public LedgerHttpAvailableFunds(
        RestClient restClient, String baseUrl, String key, ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.key = key;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PaymentContribution> contributions(MerchantId merchantId) {
        JsonNode body = get("/internal/v1/ledger/merchants/" + merchantId.value()
            + "/available-contributions", "contributions");
        List<PaymentContribution> contributions = new ArrayList<>();

        for (JsonNode item : body.get("contributions")) {
            if (!item.hasNonNull("currency") || !item.hasNonNull("paymentIntentId")
                || !item.hasNonNull("amountMinor")) {
                throw new LedgerReadUnavailableException("Ledger returned a malformed contribution");
            }

            contributions.add(new PaymentContribution(
                item.get("currency").asString(),
                item.get("paymentIntentId").asString(),
                item.get("amountMinor").asLong()
            ));
        }

        return contributions;
    }

    @Override
    public List<MerchantId> merchantsWithAnAvailableAccount() {
        JsonNode body = get("/internal/v1/ledger/available-merchants", "merchantIds");
        List<MerchantId> merchants = new ArrayList<>();

        for (JsonNode id : body.get("merchantIds")) {
            try {
                merchants.add(MerchantId.from(id.asString()));
            } catch (IllegalArgumentException malformed) {
                // A malformed id is the ledger answering badly, not a bug to abort the tick on:
                // same fail-closed skip as any other bad read (the sweeper catches this type).
                throw new LedgerReadUnavailableException(
                    "Ledger returned a malformed merchant id", malformed
                );
            }
        }

        return merchants;
    }

    private JsonNode get(String path, String requiredArray) {
        try {
            return restClient.get()
                .uri(baseUrl + path)
                .header(KEY_HEADER, key)
                // exchange() (close=true): the response is closed on EVERY path out of the handler --
                // the non-2xx and malformed-body throws included -- so no connection is leaked.
                // A non-2xx is data here, so a refusal and a transport failure take one path.
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new LedgerReadUnavailableException(
                            "Ledger read refused with status " + response.getStatusCode().value()
                        );
                    }

                    JsonNode json = objectMapper.readTree(response.bodyTo(String.class));

                    if (json == null || !json.has(requiredArray) || !json.get(requiredArray).isArray()) {
                        throw new LedgerReadUnavailableException(
                            "Ledger read returned no '" + requiredArray + "' list"
                        );
                    }

                    return json;
                });
        } catch (LedgerReadUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LedgerReadUnavailableException(
                "Could not read the ledger: " + failure.getMessage(), failure
            );
        }
    }
}
