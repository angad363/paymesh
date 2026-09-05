package com.paymesh.gateway;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limit bites, and it bites at the gateway backed by Redis. Capacity is squeezed to 3 for
 * this context so a short burst crosses it: the first requests pass, then the edge returns 429 before
 * the backend is touched again. A real Redis (the base's Testcontainer) proves the distributed path,
 * not an in-memory shortcut.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "paymesh.gateway.rate-limit.capacity=3")
@ActiveProfiles("dev")
class GatewayRateLimitTest extends GatewayIntegrationTestBase {

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetBackend() {
        WireMock.configureFor("localhost", BACKEND.port());
        WireMock.reset();
        BACKEND.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("backend-ok")));
    }

    @Test
    void refusesTheBurstOnceCapacityIsSpent() throws Exception {
        String token = validToken();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(get("/api/v1/orders", token));
        }

        // The first request is admitted...
        assertThat(statuses.get(0)).isEqualTo(200);
        // ...and within a burst of 8 against a capacity of 3, the edge starts returning 429.
        assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS.value());
        // The bucket admits no more than its capacity.
        assertThat(statuses.stream().filter(s -> s == 200).count()).isLessThanOrEqualTo(3);
    }

    private int get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gatewayUrl(path)))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
