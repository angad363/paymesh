package com.paymesh.gateway;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge, both halves of PR 5's verification: an unauthenticated call to a protected route is
 * refused AT THE GATEWAY and never reaches the backend; an authenticated one is forwarded unchanged;
 * and the routes the monolith authenticates differently (public signup, HMAC callbacks) are NOT
 * demanded a bearer token here -- they pass straight through, or the gateway would break them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayEdgeAuthTest extends GatewayIntegrationTestBase {

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetBackend() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        // The backend answers anything the gateway forwards; the tests assert on what the gateway
        // chose to forward versus refuse, not on backend behavior.
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));
    }

    @Test
    void refusesAProtectedRouteWithoutATokenAtTheEdge() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/orders", null, null);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.body()).contains("UNAUTHENTICATED");
        // The refusal happened at the edge: the backend never saw the request.
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/orders")));
    }

    @Test
    void refusesAProtectedRouteWithAnInvalidToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/orders", "Bearer not-a-real-jwt", null);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/orders")));
    }

    @Test
    void refusesAnExpiredTokenAtTheEdgeLikeTheMonolith() throws Exception {
        // A well-signed but expired token: the edge decoder mirrors the monolith's zero-skew,
        // exp-required validator, so it rejects rather than forwarding a token the monolith would 401.
        HttpResponse<String> response = send("GET", "/api/v1/orders", "Bearer " + expiredToken(), null);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/orders")));
    }

    @Test
    void refusesAnUnknownInternalPathRatherThanForwardingIt() throws Exception {
        // The edge permits only the three exact callback paths, mirroring the monolith's boundary; an
        // unknown /internal path is refused here rather than forwarded for the monolith to deny.
        HttpResponse<String> response = send("POST", "/internal/v1/not-a-callback", null, "{}");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/not-a-callback")));
    }

    @Test
    void forwardsAProtectedRouteWithAValidToken() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/orders", "Bearer " + validToken(), null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("backend-ok");
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/orders")));
    }

    @Test
    void forwardsPublicMerchantSignupWithoutAToken() throws Exception {
        // POST /api/v1/merchants is the one unauthenticated write; the edge must not demand a token
        // or self-service onboarding is impossible.
        HttpResponse<String> response = send("POST", "/api/v1/merchants", null, "{}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("backend-ok");
    }

    @Test
    void forwardsProviderCallbacksWithoutABearerToken() throws Exception {
        // Callbacks authenticate by HMAC at the monolith. If the edge demanded a JWT they would 401
        // here before the signature filter ran, stranding a payment already taken.
        HttpResponse<String> response =
            send("POST", "/internal/v1/provider-callbacks/pi_x", null, "{}");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String auth, String body)
        throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gatewayUrl(path)));
        if (auth != null) {
            request.header(HttpHeaders.AUTHORIZATION, auth);
        }
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
        if (body != null) {
            request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        }
        return http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
}
