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
 * PROVES {@code /api/v1/merchants/**} REACHES THE MERCHANT DEPLOYABLE, NOT THE MONOLITH (ADR-046
 * section 6). The prefix is a SUBSET of {@code apiRoutes}' {@code /api/**}, so without
 * {@code merchantRoutes}' {@code @Order(0)} it could silently fall through to {@code backend-uri}.
 * {@code POST /api/v1/merchants} stays public at the edge; {@code /api/v1/settlement-config} is NOT
 * routed to merchant (it moves with PR 12).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayMerchantRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer MERCHANT = new WireMockServer(options().dynamicPort());

    static {
        MERCHANT.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wireMerchant(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.merchant-uri", () -> "http://localhost:" + MERCHANT.port());
    }

    @BeforeEach
    void resetBackends() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));

        WireMock.configureFor("localhost", MERCHANT.port());
        WireMock.reset();
        MERCHANT.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("merchant-ok")));
    }

    /** Self-serve registration carries no credential and is still forwarded, to merchant. */
    @Test
    void forwardsPublicRegistrationToMerchantUnauthenticated() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/merchants", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("merchant-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/merchants")));

        WireMock.configureFor("localhost", MERCHANT.port());
        WireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/merchants")));
    }

    /**
     * Everything else under the prefix needs a token at the edge. Neither backend may see the call: a
     * precedence regression that fell through to {@code /api/**} would still get the 401, so the
     * zero-call checks on BOTH stubs are what pin the route.
     */
    @Test
    void refusesOtherMerchantRoutesAtTheEdgeWithNoToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/merchants/mrc_x", null);

        assertThat(response.statusCode()).isEqualTo(401);

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/merchants/mrc_x")));
        WireMock.configureFor("localhost", MERCHANT.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/merchants/mrc_x")));
    }

    /** Precedence over {@code /api/**}: with a valid token the call reaches merchant, not the monolith. */
    @Test
    void forwardsMerchantRoutesToMerchantWithAValidToken() throws Exception {
        HttpResponse<String> response =
            send("GET", "/api/v1/merchants/mrc_x/api-credentials", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("merchant-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/merchants/mrc_x/api-credentials")));

        WireMock.configureFor("localhost", MERCHANT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/merchants/mrc_x/api-credentials")));
    }

    /** Settlement config is NOT merchant's (ADR-046 section 1): it still reaches the monolith. */
    @Test
    void doesNotRouteSettlementConfigToMerchant() throws Exception {
        HttpResponse<String> response =
            send("GET", "/api/v1/settlement-config", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("backend-ok");

        WireMock.configureFor("localhost", MERCHANT.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/settlement-config")));
    }

    /** Precedence the other way: ordinary {@code /api/**} routes are unaffected. */
    @Test
    void stillForwardsOrdinaryApiRoutesToTheMonolith() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/orders", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("backend-ok");
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
