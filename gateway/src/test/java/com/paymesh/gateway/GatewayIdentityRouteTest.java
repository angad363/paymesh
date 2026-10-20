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
 * PROVES {@code /api/v1/auth/**} AND {@code /api/v1/users/**} REACH THE IDENTITY DEPLOYABLE, NOT THE
 * MONOLITH (ADR-045 section 6).
 *
 * <p>Same precedence hazard {@link GatewayWebhookRouteTest}/{@link GatewayEngagementRouteTest} prove
 * for their own prefixes: both paths here are SUBSETS of {@code apiRoutes}' {@code /api/**}, so
 * without {@code identityRoutes}' explicit {@code @Order(0)} a login or a user-admin call could
 * silently fall through to {@code backend-uri} instead -- exactly the regression a client would only
 * discover in production, and on the login path specifically. Two stand-in backends prove which one
 * actually received each call.
 *
 * <p>{@code /api/v1/auth/**} IS PROVEN TO PASS THROUGH UNAUTHENTICATED: {@code SecurityConfiguration}
 * already {@code permitAll()}s this prefix (ADR-040), because getting a token cannot require one.
 * {@code /api/v1/users/**} carries no matcher of its own there, so it stays behind
 * {@code anyRequest().authenticated()} and is proven to need a bearer token at the edge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayIdentityRouteTest extends GatewayIntegrationTestBase {

    private static final WireMockServer IDENTITY = new WireMockServer(options().dynamicPort());

    static {
        IDENTITY.start();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void wireIdentity(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.identity-uri", () -> "http://localhost:" + IDENTITY.port());
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

        WireMock.configureFor("localhost", IDENTITY.port());
        WireMock.reset();
        IDENTITY.stubFor(
            any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("identity-ok"))
        );
    }

    /**
     * LOGIN REACHES IDENTITY WITH NO BEARER TOKEN AT ALL, AND IS STILL FORWARDED, NOT 401'D. The edge
     * permits this prefix unauthenticated (ADR-040) -- refusing it here would make it impossible to
     * ever mint the first token.
     */
    @Test
    void forwardsAuthLoginToIdentityUnauthenticated() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/auth/login", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("identity-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/auth/login")));

        WireMock.configureFor("localhost", IDENTITY.port());
        WireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/auth/login")));
    }

    @Test
    void forwardsAuthRegisterToIdentityNotTheMonolith() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/auth/register", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("identity-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/auth/register")));

        WireMock.configureFor("localhost", IDENTITY.port());
        WireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/auth/register")));
    }

    /**
     * {@code /api/v1/users/**} STAYS JWT-GATED AT THE EDGE: no bearer token means a 401 from the
     * gateway itself, and neither backend ever sees the request.
     */
    @Test
    void refusesUsersRouteAtTheEdgeWithNoToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/users", null);

        assertThat(response.statusCode()).isEqualTo(401);

        WireMock.configureFor("localhost", IDENTITY.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/users")));
    }

    /** With a valid token, {@code /api/v1/users} reaches identity, not the monolith. */
    @Test
    void forwardsUsersRouteToIdentityWithAValidToken() throws Exception {
        HttpResponse<String> response =
            send("GET", "/api/v1/users", "Bearer " + validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("identity-ok");

        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/users")));

        WireMock.configureFor("localhost", IDENTITY.port());
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/users")));
    }

    /** Precedence: an ordinary {@code /api/**} route is unaffected and still reaches the monolith. */
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
