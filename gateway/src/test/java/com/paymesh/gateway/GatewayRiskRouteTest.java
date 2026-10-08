package com.paymesh.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
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
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROVES {@code /internal/v1/risk-evaluations} REACHES THE RISK DEPLOYABLE, NOT THE MONOLITH
 * (ADR-044 section 6).
 *
 * <p>Same precedence hazard {@link GatewayEngagementRouteTest} proves for engagement's internal
 * routes: {@code /internal/v1/risk-evaluations/**} is a SUBSET of {@code internalCallbackRoutes}'
 * {@code /internal/**}, so without {@code riskRoutes}' explicit {@code @Order(0)} a request could
 * silently fall through to {@code backend-uri} instead -- exactly the regression a client would
 * only discover in production, and on the confirm path specifically. Two stand-in backends prove
 * which one actually received each call.
 * <p>
 * NO {@code Authorization} HEADER IS SENT, unlike every other route test in this suite: this route
 * is machine-to-machine, authenticated by a shared key the RECEIVING service checks, not a JWT the
 * gateway validates -- see {@code RiskEvaluationKeyFilter}. The gateway itself does not inspect
 * this route's auth at all, the same treatment {@code internalCallbackRoutes} gives provider
 * callbacks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayRiskRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer RISK = new WireMockServer(options().dynamicPort());

    static {
        RISK.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wireRisk(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.risk-uri", () -> "http://localhost:" + RISK.port());
    }

    @BeforeEach
    void resetBackends() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));

        WireMock.configureFor("localhost", RISK.port());
        WireMock.reset();
        RISK.stubFor(
            any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"permitted\":true}"))
        );
    }

    @Test
    void forwardsRiskEvaluationsToRiskNotTheMonolith() throws Exception {
        HttpResponse<String> response = send("/internal/v1/risk-evaluations");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"permitted\":true}");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/risk-evaluations")));

        WireMock.configureFor("localhost", RISK.port());
        WireMock.verify(1, postRequestedFor(urlPathEqualTo("/internal/v1/risk-evaluations")));
    }

    @Test
    void stillForwardsOrdinaryInternalRoutesToTheMonolith() throws Exception {
        HttpResponse<String> response = send("/internal/v1/provider-callbacks");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("backend-ok");
    }

    private HttpResponse<String> send(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gatewayUrl(path)))
            .header("X-PayMesh-Risk-Key", "irrelevant-to-this-test")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
