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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROVES THE LEDGER PREFIXES REACH THE LEDGER DEPLOYABLE, NOT THE MONOLITH (ADR-050 section 6).
 * {@code /api/v1/balances} is a SUBSET of {@code /api/**} and {@code /internal/v1/ledger/**} a SUBSET
 * of {@code /internal/**}, so {@code ledgerRoutes}' {@code @Order(0)} is what keeps them from
 * silently falling through to {@code backend-uri}. The zero-call checks on the monolith stub are what
 * pin the route.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayLedgerRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer LEDGER = new WireMockServer(options().dynamicPort());

    static {
        LEDGER.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wireLedger(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.ledger-uri", () -> "http://localhost:" + LEDGER.port());
    }

    /**
     * The payment prefixes (ADR-048) have their own route and their own {@code payment-uri}. This test
     * uses {@code /api/v1/orders} only as "a route that stays on the monolith", so the stand-in
     * backend answers for payment too. GatewayPaymentRouteTest pins the real destination.
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

        WireMock.configureFor("localhost", LEDGER.port());
        WireMock.reset();
        LEDGER.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ledger-ok")));
    }

    @Test
    void forwardsTheBalanceReadToTheLedgerWithAValidToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/balances", "Bearer " + validToken(), null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ledger-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/balances")));
        WireMock.configureFor("localhost", LEDGER.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/balances")));
    }

    /** Client traffic still needs a token at the edge; neither backend may see the unauthenticated call. */
    @Test
    void refusesTheBalanceReadAtTheEdgeWithNoToken() throws Exception {
        assertThat(send("GET", "/api/v1/balances", null, null).statusCode()).isEqualTo(401);

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(anyUrl()));
        WireMock.configureFor("localhost", LEDGER.port());
        WireMock.verify(0, getRequestedFor(anyUrl()));
    }

    /**
     * Settlement's read: no bearer token (it holds a shared read key), forwarded with the key header
     * intact, to the ledger and never the monolith.
     */
    @Test
    void forwardsTheInternalLedgerReadToTheLedgerWithTheKeyAndNoToken() throws Exception {
        HttpResponse<String> list = send("GET", "/internal/v1/ledger/available-merchants", null, "k");
        HttpResponse<String> nested = send(
            "GET", "/internal/v1/ledger/merchants/mrc_x/available-contributions", null, "k");

        assertThat(list.body()).isEqualTo("ledger-ok");
        assertThat(nested.body()).isEqualTo("ledger-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(anyUrl()));
        WireMock.configureFor("localhost", LEDGER.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/internal/v1/ledger/available-merchants"))
            .withHeader("X-PayMesh-Ledger-Key", WireMock.equalTo("k")));
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/internal/v1/ledger/merchants/mrc_x/available-contributions")));
    }

    /** Precedence the other way: other /internal and /api routes are unaffected. */
    @Test
    void otherInternalAndApiRoutesStillReachTheMonolith() throws Exception {
        HttpResponse<String> api = send("GET", "/api/v1/orders", "Bearer " + validToken(), null);
        HttpResponse<String> internal = send("POST", "/internal/v1/refund-callbacks/simulator", null, null);

        assertThat(api.body()).isEqualTo("backend-ok");
        assertThat(internal.body()).isEqualTo("backend-ok");
    }

    private HttpResponse<String> send(String method, String path, String auth, String ledgerKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gatewayUrl(path)));
        if (auth != null) {
            request.header(HttpHeaders.AUTHORIZATION, auth);
        }
        if (ledgerKey != null) {
            request.header("X-PayMesh-Ledger-Key", ledgerKey);
        }
        return http.send(
            request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
