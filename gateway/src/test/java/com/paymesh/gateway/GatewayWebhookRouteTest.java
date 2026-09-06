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
 * PROVES {@code /api/v1/webhook-endpoints/**} REACHES WEBHOOK, NOT THE MONOLITH (ADR-042 section 6).
 *
 * <p>This is the one route in {@code RoutesConfiguration} where getting the order wrong is silent:
 * every other pair of predicates in that class is disjoint ({@code /api/**} vs {@code /internal/**}
 * vs {@code /sim/**}), so which {@code RouterFunction} bean the mapping tries first never mattered
 * for them. {@code /api/v1/webhook-endpoints/**} is a SUBSET of {@code apiRoutes}' {@code /api/**},
 * so without {@code webhookRoutes}' explicit {@code @Order} a webhook request could silently fall
 * through to {@code backend-uri} instead -- exactly the regression a client would only discover in
 * production. Two stand-in backends prove which one actually received the call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayWebhookRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer WEBHOOK = new WireMockServer(options().dynamicPort());

    static {
        WEBHOOK.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wireWebhook(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.webhook-uri", () -> "http://localhost:" + WEBHOOK.port());
    }

    @BeforeEach
    void resetBackends() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));

        WireMock.configureFor("localhost", WEBHOOK.port());
        WireMock.reset();
        WEBHOOK.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("webhook-ok")));
    }

    @Test
    void forwardsWebhookEndpointRoutesToWebhookNotTheMonolith() throws Exception {
        HttpResponse<String> response =
            send("GET", "/api/v1/webhook-endpoints", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("webhook-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/webhook-endpoints")));

        WireMock.configureFor("localhost", WEBHOOK.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/webhook-endpoints")));
    }

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
