package com.paymesh.gateway.config;

import com.paymesh.gateway.ApiErrorResponse;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.HandlerFilterFunction;
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

    private static final Logger LOG = LoggerFactory.getLogger(RoutesConfiguration.class);

    /** The house error body for a throttled request -- same {@code {code,message}} shape as the 401. */
    private static final ApiErrorResponse RATE_LIMITED = ApiErrorResponse.of(
        "RATE_LIMITED", "Too many requests. Retry shortly.");

    @Bean
    RouterFunction<ServerResponse> apiRoutes(
        @Value("${paymesh.gateway.backend-uri}") String backendUri,
        @Value("${paymesh.gateway.rate-limit.capacity:100}") long capacity,
        @Value("${paymesh.gateway.rate-limit.period-seconds:60}") long periodSeconds
    ) {
        return route("paymesh-api")
            .route(path("/api/**"), http())
            .before(uri(backendUri))
            .filter(rateLimitGuard(rateLimit(config -> config
                .setCapacity(capacity)
                .setPeriod(Duration.ofSeconds(periodSeconds))
                .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
                .setKeyResolver(RoutesConfiguration::clientKey))))
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
     * Wraps the rate-limit filter so a Redis outage cannot take down the money path's front door, and
     * so a throttled request still gets the house error body.
     *
     * <ul>
     *   <li><b>Fail OPEN on a rate-limiter failure.</b> Redis is a throwaway counter store, not an
     *       authority (CLAUDE.md: it may fail without corrupting payments). If it is unreachable, the
     *       bucket4j filter throws <em>before</em> it forwards; we log and forward unlimited rather
     *       than 500 every {@code /api} call. The catch is narrowed to a Lettuce {@link RedisException}
     *       in the cause chain so a <em>backend</em> failure is NOT mistaken for a limiter failure --
     *       re-forwarding a non-idempotent write would be far worse than surfacing the error.</li>
     *   <li><b>Give 429 the house body.</b> bucket4j denies with a bare status; we rewrite it to the
     *       same {@code {code,message}} shape the edge already gives 401, preserving the limiter's
     *       headers, so a client parses one error shape everywhere.</li>
     * </ul>
     */
    private static HandlerFilterFunction<ServerResponse, ServerResponse> rateLimitGuard(
        HandlerFilterFunction<ServerResponse, ServerResponse> delegate
    ) {
        return (request, next) -> {
            ServerResponse response;
            try {
                response = delegate.filter(request, next);
            } catch (Exception e) {
                if (isRedisFailure(e)) {
                    LOG.warn("Rate limiter store unavailable; failing open for {}", request.path(), e);
                    return next.handle(request);
                }
                throw e;
            }
            if (response.statusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers -> headers.addAll(response.headers()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(RATE_LIMITED);
            }
            return response;
        };
    }

    private static boolean isRedisFailure(Throwable t) {
        for (Throwable cause = t; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof RedisException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rate-limit bucket key: the caller's IP. The unauthenticated signup route has no subject to key
     * on, and IP is the only stable identity a would-be abuser presents there.
     * <p>
     * ponytail: {@code getRemoteAddr()} is the socket peer, correct while the gateway IS the edge --
     * and deliberately so, because trusting a client-supplied {@code X-Forwarded-For} here would let
     * anyone spoof their IP and dodge the per-IP limit. Put a TRUSTED load balancer in front and the
     * switch is one property, {@code server.forward-headers-strategy=framework}, which makes
     * {@code getRemoteAddr()} honor the LB's {@code X-Forwarded-For}; do NOT set it while the gateway
     * is directly reachable, or the limit becomes spoofable.
     */
    private static String clientKey(ServerRequest request) {
        return request.servletRequest().getRemoteAddr();
    }
}
