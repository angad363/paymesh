package com.paymesh.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROVES THE SETTLEMENT PREFIXES REACH THE SETTLEMENT DEPLOYABLE, NOT THE MONOLITH (ADR-047 section
 * 3). {@code /internal/v1/payout-callbacks/**} is a SUBSET of {@code /internal/**} and the client
 * prefixes are SUBSETS of {@code /api/**}, so {@code settlementRoutes}' {@code @Order(0)} is what
 * keeps them from silently falling through to {@code backend-uri}. The zero-call checks on the
 * monolith stub are what pin the route.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewaySettlementRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer SETTLEMENT = new WireMockServer(options().dynamicPort());

    static {
        SETTLEMENT.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wireSettlement(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.settlement-uri", () -> "http://localhost:" + SETTLEMENT.port());
    }

    /**
     * The payment prefixes (ADR-048) have their own route and their own {@code payment-uri}. This test
     * uses {@code /api/v1/orders} (or a callback prefix) only as "an authenticated route", so the
     * stand-in backend answers for payment too. GatewayPaymentRouteTest pins the real destination.
     */
    @DynamicPropertySource
    static void paymentUriIsTheStandInBackend(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.payment-uri", () -> "http://localhost:" + BACKEND.port());
    }

    @BeforeEach
    void resetBackends() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));

        WireMock.configureFor("localhost", SETTLEMENT.port());
        WireMock.reset();
        SETTLEMENT.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("settlement-ok")));
    }

    /** The provider holds no PayMesh token: unauthenticated at the edge, HMAC-checked at the deployable. */
    @Test
    void forwardsPayoutCallbacksToSettlementWithoutABearerToken() throws Exception {
        HttpResponse<String> response = send("POST", "/internal/v1/payout-callbacks/simulator", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("settlement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/payout-callbacks/simulator")));
        WireMock.configureFor("localhost", SETTLEMENT.port());
        WireMock.verify(1, postRequestedFor(urlPathEqualTo("/internal/v1/payout-callbacks/simulator")));
    }

    @Test
    void forwardsSettlementReadsToSettlementWithAValidToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/settlements/stl_x", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("settlement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/settlements/stl_x")));
        WireMock.configureFor("localhost", SETTLEMENT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/settlements/stl_x")));
    }

    @Test
    void forwardsSettlementConfigToSettlementNotMerchantOrTheMonolith() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/settlement-config", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("settlement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/settlement-config")));
        WireMock.configureFor("localhost", SETTLEMENT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/settlement-config")));
    }

    /** Client traffic still needs a token at the edge; neither backend may see the unauthenticated call. */
    @Test
    void refusesSettlementReadsAtTheEdgeWithNoToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/settlements", null);

        assertThat(response.statusCode()).isEqualTo(401);

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/settlements")));
        WireMock.configureFor("localhost", SETTLEMENT.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/settlements")));
    }

    /** Precedence the other way: other /internal and /api routes are unaffected. */
    @Test
    void otherInternalAndApiRoutesStillReachTheMonolith() throws Exception {
        HttpResponse<String> api = send("GET", "/api/v1/orders", "Bearer " + validToken());
        HttpResponse<String> internal = send("POST", "/internal/v1/refund-callbacks/simulator", null);

        assertThat(api.body()).isEqualTo("backend-ok");
        assertThat(internal.body()).isEqualTo("backend-ok");
    }

    /** The ledger read is service-to-service only: the edge answers 404 and the monolith sees nothing. */
    @Test
    void doesNotExposeTheInternalLedgerReadThroughTheGateway() throws Exception {
        // An unauthenticated call never gets past edge auth (401); the hole was for any VALID token.
        assertThat(send("GET", "/internal/v1/ledger/available-merchants", null).statusCode()).isEqualTo(401);

        HttpResponse<String> response = send(
            "GET", "/internal/v1/ledger/available-merchants", "Bearer " + validToken());
        HttpResponse<String> nested = send(
            "GET", "/internal/v1/ledger/merchants/mrc_x/available-contributions", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(nested.statusCode()).isEqualTo(404);

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/internal/v1/ledger/available-merchants")));
        WireMock.verify(0, getRequestedFor(anyUrl()));
        WireMock.configureFor("localhost", SETTLEMENT.port());
        WireMock.verify(0, getRequestedFor(anyUrl()));
    }

    private HttpResponse<String> send(String method, String path, String auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gatewayUrl(path)));
        if (auth != null) {
            request.header(HttpHeaders.AUTHORIZATION, auth);
        }
        return http.send(
            request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
