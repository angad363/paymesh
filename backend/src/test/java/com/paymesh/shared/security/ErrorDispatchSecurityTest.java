package com.paymesh.shared.security;

import com.paymesh.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A FAILURE MUST REPORT ITSELF, NOT A LIE ABOUT AUTHENTICATION.
 * <p>
 * When a request fails after the security chain has already let it through, the servlet container
 * re-dispatches it to {@code /error} with {@code DispatcherType.ERROR}, and Spring Security's chain
 * runs on that dispatch too. {@code /error} matches no rule in {@link SecurityConfiguration} except
 * {@code anyRequest().authenticated()}, so on an anonymous request the authorization filter denies
 * the ERROR dispatch and the entry point overwrites the real answer with
 * {@code 401 UNAUTHENTICATED}.
 * <p>
 * That is not cosmetic. It reports every 4xx and <b>every 500</b> on a public route as an
 * authentication failure: the operator reads "someone forgot a token", the client retries with a
 * token and gets the same 401, and the actual defect is invisible in the response and
 * indistinguishable from ordinary unauthenticated traffic in the access log. It is what hid a real
 * 500 on the provider-callback route from the Postman collection, which reported it as
 * {@code UNAUTHENTICATED}.
 * <p>
 * <b>This test needs a real servlet container.</b> MockMvc does not perform the container's ERROR
 * dispatch -- it returns the raw 415 with an empty body and never re-enters the filter chain -- so
 * a MockMvc version of this test passes with the bug present. Hence {@code RANDOM_PORT} and a plain
 * JDK HTTP client.
 * <p>
 * The route is deliberately {@code permitAll}: {@code POST /api/v1/merchants} is public onboarding,
 * so nothing about this request is an authentication problem and 401 can only be wrong.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ErrorDispatchSecurityTest {

    @LocalServerPort
    private int port;

    @Test
    void reportsTheRealFailureOnAPublicRouteRatherThanAnAuthenticationError() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/merchants"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("this is not JSON"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.body())
            .as("an error on a permitAll route must not be relabelled an authentication failure")
            .doesNotContain("UNAUTHENTICATED");
        assertThat(response.statusCode())
            .as("the caller must be told what actually went wrong")
            .isEqualTo(415);
    }
}
