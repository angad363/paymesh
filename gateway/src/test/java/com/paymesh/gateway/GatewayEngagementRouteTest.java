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
 * PROVES {@code /api/v1/reports/**}, {@code /api/v1/report-exports/**},
 * {@code /internal/v1/notifications/**}, {@code /internal/v1/audit-events/**} and
 * {@code /internal/v1/audit-exports/**} REACH ENGAGEMENT, NOT THE MONOLITH (ADR-043 section 6).
 *
 * <p>Same precedence hazard {@link GatewayWebhookRouteTest} proves for webhook: the reports/exports
 * prefixes are SUBSETS of {@code apiRoutes}' {@code /api/**} and the three internal prefixes are
 * SUBSETS of {@code internalCallbackRoutes}' {@code /internal/**}, so without {@code
 * engagementRoutes}'/{@code engagementInternalRoutes}' explicit {@code @Order(0)} a request could
 * silently fall through to {@code backend-uri} instead -- exactly the regression a client would only
 * discover in production. Two stand-in backends prove which one actually received each call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayEngagementRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer ENGAGEMENT = new WireMockServer(options().dynamicPort());

    static {
        ENGAGEMENT.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wireEngagement(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.engagement-uri", () -> "http://localhost:" + ENGAGEMENT.port());
    }

    @BeforeEach
    void resetBackends() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));

        WireMock.configureFor("localhost", ENGAGEMENT.port());
        WireMock.reset();
        ENGAGEMENT.stubFor(
            any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("engagement-ok"))
        );
    }

    @Test
    void forwardsReportRoutesToEngagementNotTheMonolith() throws Exception {
        HttpResponse<String> response =
            send("GET", "/api/v1/reports/payment-summary", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engagement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/reports/payment-summary")));

        WireMock.configureFor("localhost", ENGAGEMENT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/reports/payment-summary")));
    }

    @Test
    void forwardsReportExportRoutesToEngagementNotTheMonolith() throws Exception {
        HttpResponse<String> response =
            send("GET", "/api/v1/report-exports/rex_1", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engagement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/report-exports/rex_1")));

        WireMock.configureFor("localhost", ENGAGEMENT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/report-exports/rex_1")));
    }

    @Test
    void forwardsInternalNotificationRoutesToEngagementNotTheMonolith() throws Exception {
        HttpResponse<String> response =
            send("GET", "/internal/v1/notifications/nfn_1", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engagement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/internal/v1/notifications/nfn_1")));

        WireMock.configureFor("localhost", ENGAGEMENT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/internal/v1/notifications/nfn_1")));
    }

    @Test
    void forwardsInternalAuditEventRoutesToEngagementNotTheMonolith() throws Exception {
        HttpResponse<String> response =
            send("GET", "/internal/v1/audit-events", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engagement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/internal/v1/audit-events")));

        WireMock.configureFor("localhost", ENGAGEMENT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/internal/v1/audit-events")));
    }

    @Test
    void forwardsInternalAuditExportRoutesToEngagementNotTheMonolith() throws Exception {
        HttpResponse<String> response =
            send("GET", "/internal/v1/audit-exports/aex_1", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engagement-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/internal/v1/audit-exports/aex_1")));

        WireMock.configureFor("localhost", ENGAGEMENT.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/internal/v1/audit-exports/aex_1")));
    }

    @Test
    void stillForwardsOrdinaryApiRoutesToTheMonolith() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/orders", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("backend-ok");
    }

    @Test
    void stillForwardsOrdinaryInternalRoutesToTheMonolith() throws Exception {
        HttpResponse<String> response = send("POST", "/internal/v1/provider-callbacks", null);

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
