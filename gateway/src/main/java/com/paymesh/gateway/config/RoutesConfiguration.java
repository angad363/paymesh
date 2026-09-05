package com.paymesh.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Duration;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions.rateLimit;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * The route table. Today every route points at the one monolith on {@code backend-uri}; in 3B each
 * prefix re-points at its own service and nothing else in this class changes. That re-pointing being
 * a one-line edit here, not a client change, is the entire reason the gateway exists before the
 * services do.
 *
 * <p>Two route groups, split by whether the rate limit applies:
 * <ul>
 *   <li><b>API traffic</b> ({@code /api/**}) is rate limited. This is client traffic, and the one
 *       genuinely unauthenticated write on it -- {@code POST /api/v1/merchants} -- is the abuse
 *       vector the monolith always flagged as needing a limit.</li>
 *   <li><b>Callbacks and the simulator</b> ({@code /internal/**}, {@code /sim/**}) are forwarded
 *       without a limit. A provider retrying a delivery it is contractually required to retry must
 *       not be throttled into a failure that strands money already moved; these authenticate by HMAC
 *       / shared key at the monolith and are not a public abuse surface.</li>
 * </ul>
 */
@Configuration
public class RoutesConfiguration {

    @Bean
    RouterFunction<ServerResponse> apiRoutes(
        @Value("${paymesh.gateway.backend-uri}") String backendUri,
        @Value("${paymesh.gateway.rate-limit.capacity:100}") long capacity,
        @Value("${paymesh.gateway.rate-limit.period-seconds:60}") long periodSeconds
    ) {
        return route("paymesh-api")
            .route(path("/api/**"), http())
            .before(uri(backendUri))
            .filter(rateLimit(config -> config
                .setCapacity(capacity)
                .setPeriod(Duration.ofSeconds(periodSeconds))
                .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
                .setKeyResolver(RoutesConfiguration::clientKey)))
            .build();
    }

    @Bean
    RouterFunction<ServerResponse> passthroughRoutes(
        @Value("${paymesh.gateway.backend-uri}") String backendUri
    ) {
        return route("paymesh-passthrough")
            .route(path("/internal/**"), http())
            .route(path("/sim/**"), http())
            .before(uri(backendUri))
            .build();
    }

    /**
     * Rate-limit bucket key: the caller's IP. The unauthenticated signup route has no subject to key
     * on, and IP is the only stable identity a would-be abuser presents there.
     * <p>
     * ponytail: {@code getRemoteAddr()} is the socket peer, correct when the gateway is the edge. Put
     * a real load balancer in front and this must read the leftmost {@code X-Forwarded-For} hop
     * instead, or every client shares the LB's IP and one noisy tenant limits everyone.
     */
    private static String clientKey(ServerRequest request) {
        return request.servletRequest().getRemoteAddr();
    }
}
