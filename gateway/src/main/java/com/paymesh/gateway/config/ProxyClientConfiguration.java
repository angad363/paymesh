package com.paymesh.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

/**
 * The outbound proxy client. The webmvc gateway builds its forwarding {@code RestClient} from
 * whatever {@link ClientHttpRequestFactory} it finds; we give it a JDK client pinned to HTTP/1.1.
 *
 * <p>Left to negotiate, the JDK client offers HTTP/2, and a plaintext (h2c) POST upgrade to a backend
 * that does not speak it cleanly gets its request body cancelled mid-stream ({@code RST_STREAM}). The
 * monolith is a plaintext HTTP/1.1 origin, so HTTP/1.1 is what this hop actually is; pinning it makes
 * that explicit and drops the failed upgrade dance. Revisit if a backend is ever fronted by TLS h2.
 */
@Configuration
public class ProxyClientConfiguration {

    @Bean
    ClientHttpRequestFactory gatewayClientHttpRequestFactory() {
        return new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
    }
}
