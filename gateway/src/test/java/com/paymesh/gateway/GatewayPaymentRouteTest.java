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
 * PROVES THE PAYMENT PREFIXES REACH THE PAYMENT DEPLOYABLE, NOT THE MONOLITH (ADR-048 sections 5
 * and 6). Each client prefix is a SUBSET of {@code /api/**} and each callback prefix a SUBSET of
 * {@code /internal/**}, so {@code paymentRoutes}' and {@code paymentInternalRoutes}' {@code
 * @Order(0)} is what keeps them from silently falling through to {@code backend-uri}. The
 * zero-call checks on the monolith stub are what pin the routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayPaymentRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer PAYMENT = new WireMockServer(options().dynamicPort());

    static {
        PAYMENT.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wirePayment(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.payment-uri", () -> "http://localhost:" + PAYMENT.port());
    }

    @BeforeEach
    void resetBackends() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));

        WireMock.configureFor("localhost", PAYMENT.port());
        WireMock.reset();
        PAYMENT.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("payment-ok")));
    }

    @Test
    void forwardsEveryClientPrefixToPaymentAndNeverToTheMonolith() throws Exception {
        for (String path : new String[] {
            "/api/v1/customers", "/api/v1/customers/cus_x", "/api/v1/orders", "/api/v1/orders/ord_x",
            "/api/v1/payment-intents", "/api/v1/payment-intents/pi_x/confirm",
            "/api/v1/refunds", "/api/v1/refunds/ref_x"
        }) {
            HttpResponse<String> response = send("POST", path, "Bearer " + validToken());

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("payment-ok");

            WireMock.configureFor("localhost", BACKEND.port());
            WireMock.verify(0, postRequestedFor(urlPathEqualTo(path)));
            WireMock.configureFor("localhost", PAYMENT.port());
            WireMock.verify(1, postRequestedFor(urlPathEqualTo(path)));
        }
    }

    /** The provider holds no PayMesh token: unauthenticated at the edge, HMAC-checked at the deployable. */
    @Test
    void forwardsProviderAndRefundCallbacksToPaymentWithoutABearerToken() throws Exception {
        for (String path : new String[] {
            "/internal/v1/provider-callbacks/simulator", "/internal/v1/refund-callbacks/simulator"
        }) {
            HttpResponse<String> response = send("POST", path, null);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("payment-ok");

            WireMock.configureFor("localhost", BACKEND.port());
            WireMock.verify(0, postRequestedFor(urlPathEqualTo(path)));
            WireMock.configureFor("localhost", PAYMENT.port());
            WireMock.verify(1, postRequestedFor(urlPathEqualTo(path)));
        }
    }

    /** Client traffic still needs a token at the edge; neither backend may see the unauthenticated call. */
    @Test
    void refusesClientPrefixesAtTheEdgeWithNoToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/orders", null);

        assertThat(response.statusCode()).isEqualTo(401);

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(anyUrl()));
        WireMock.configureFor("localhost", PAYMENT.port());
        WireMock.verify(0, getRequestedFor(anyUrl()));
    }

    /** Precedence the other way: other /api and /internal routes are unaffected. */
    @Test
    void otherApiAndInternalRoutesStillReachTheMonolith() throws Exception {
        HttpResponse<String> api = send("GET", "/api/v1/ledger/accounts", "Bearer " + validToken());
        HttpResponse<String> internal = send("GET", "/internal/v1/reconciliation/runs", "Bearer " + validToken());

        assertThat(api.body()).isEqualTo("backend-ok");
        assertThat(internal.body()).isEqualTo("backend-ok");

        WireMock.configureFor("localhost", PAYMENT.port());
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
