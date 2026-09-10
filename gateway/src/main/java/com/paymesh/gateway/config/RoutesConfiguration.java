package com.paymesh.gateway.config;

import com.paymesh.gateway.ApiErrorResponse;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
 * The route table. {@code /sim/**} is the first prefix to actually re-point (ADR-041, PR 6): the
 * provider simulator left the monolith for its own deployable, and this is the one line that
 * changed to make that true from the client's side -- no client, no Postman request, addressed the
 * simulator directly, only through this gateway or the monolith's own (now-removed) route. Every
 * other prefix still points at the one monolith on {@code backend-uri}; each re-points the same way,
 * in its own PR, as its capability leaves.
 *
 * <p>Two route groups, split by whether the rate limit applies:
 * <ul>
 *   <li><b>API traffic</b> ({@code /api/**}) is rate limited. This is client traffic, and the one
 *       genuinely unauthenticated write on it -- {@code POST /api/v1/merchants} -- is the abuse
 *       vector the monolith always flagged as needing a limit.</li>
 *   <li><b>Callbacks and the simulator</b> ({@code /internal/**} to the monolith, {@code /sim/**} to
 *       provider-sim) are forwarded without a limit. A provider retrying a delivery it is
 *       contractually required to retry must not be throttled into a failure that strands money
 *       already moved; these authenticate by HMAC / shared key at the receiving service and are not
 *       a public abuse surface.</li>
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
    RouterFunction<ServerResponse> internalCallbackRoutes(
        @Value("${paymesh.gateway.backend-uri}") String backendUri
    ) {
        return route("paymesh-internal")
            .route(path("/internal/**"), http())
            .before(uri(backendUri))
            .build();
    }

    /**
     * THE SECOND RE-POINTED ROUTE (ADR-042, PR 7), same shape as {@link #providerSimRoutes}: webhook
     * left the monolith for its own deployable, so its prefix now forwards to that deployable's own
     * port instead of {@code backend-uri}.
     * <p>
     * {@code @Order} IS LOAD-BEARING HERE, UNLIKE ANY OTHER ROUTE IN THIS CLASS. Every other pair of
     * predicates in this file is disjoint ({@code /api/**} versus {@code /internal/**} versus
     * {@code /sim/**}), so which {@code RouterFunction} bean the mapping tries first never mattered.
     * {@code /api/v1/webhook-endpoints/**} is a SUBSET of {@link #apiRoutes}' {@code /api/**}, so
     * without an explicit order the two could compose in either direction and every webhook request
     * would silently fall through to the monolith on an unlucky bean-registration order. An explicit
     * value below {@link #apiRoutes}' default (unordered = lowest precedence) is what actually
     * guarantees this one is tried first, not source position in this file.
     * <p>
     * Rate limited, same as every other {@code /api/**} route: it is authenticated client traffic,
     * not a callback.
     */
    @Bean
    @Order(0)
    RouterFunction<ServerResponse> webhookRoutes(
        @Value("${paymesh.gateway.webhook-uri}") String webhookUri,
        @Value("${paymesh.gateway.rate-limit.capacity:100}") long capacity,
        @Value("${paymesh.gateway.rate-limit.period-seconds:60}") long periodSeconds
    ) {
        return route("paymesh-webhook")
            .route(path("/api/v1/webhook-endpoints/**"), http())
            .before(uri(webhookUri))
            .filter(rateLimitGuard(rateLimit(config -> config
                .setCapacity(capacity)
                .setPeriod(Duration.ofSeconds(periodSeconds))
                .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
                .setKeyResolver(RoutesConfiguration::clientKey))))
            .build();
    }

    /**
     * THE THIRD RE-POINTED ROUTE (ADR-043, PR 8), same shape as {@link #webhookRoutes}: Notification,
     * Reporting and Audit left the monolith together for the engagement deployable, so their two
     * public prefixes now forward to that deployable's own port instead of {@code backend-uri}.
     * <p>
     * {@code @Order(0)} for the same reason {@link #webhookRoutes} carries it: {@code
     * /api/v1/reports/**} and {@code /api/v1/report-exports/**} are SUBSETS of {@link #apiRoutes}'
     * {@code /api/**}, so without an explicit order the two could compose in either direction and a
     * reporting request could silently fall through to the monolith. Rate limited, same as every
     * other {@code /api/**} route: it is authenticated client traffic, not a callback.
     */
    @Bean
    @Order(0)
    RouterFunction<ServerResponse> engagementRoutes(
        @Value("${paymesh.gateway.engagement-uri}") String engagementUri,
        @Value("${paymesh.gateway.rate-limit.capacity:100}") long capacity,
        @Value("${paymesh.gateway.rate-limit.period-seconds:60}") long periodSeconds
    ) {
        return route("paymesh-engagement")
            .route(path("/api/v1/reports/**").or(path("/api/v1/report-exports/**")), http())
            .before(uri(engagementUri))
            .filter(rateLimitGuard(rateLimit(config -> config
                .setCapacity(capacity)
                .setPeriod(Duration.ofSeconds(periodSeconds))
                .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
                .setKeyResolver(RoutesConfiguration::clientKey))))
            .build();
    }

    /**
     * THE INTERNAL HALF OF ADR-043's RE-POINT: the platform-staff read surfaces for Notification and
     * Audit, forwarded to the engagement deployable unlimited -- the same posture
     * {@link #internalCallbackRoutes} gives every {@code /internal/**} route, because these are
     * authenticated by JWT + role, not a public abuse surface.
     * <p>
     * {@code @Order(0)} for the same subset reason {@link #engagementRoutes} carries it: these three
     * prefixes are SUBSETS of {@link #internalCallbackRoutes}' {@code /internal/**}.
     */
    @Bean
    @Order(0)
    RouterFunction<ServerResponse> engagementInternalRoutes(
        @Value("${paymesh.gateway.engagement-uri}") String engagementUri
    ) {
        return route("paymesh-engagement-internal")
            .route(
                path("/internal/v1/notifications/**")
                    .or(path("/internal/v1/audit-events/**"))
                    .or(path("/internal/v1/audit-exports/**")),
                http()
            )
            .before(uri(engagementUri))
            .build();
    }

    /**
     * THE FIRST RE-POINTED ROUTE (ADR-041). Every other group in this class still forwards to
     * {@code backend-uri}; this one forwards to the provider simulator's own deployable, because it
     * is the first capability to actually leave the monolith's process. A separate
     * {@code RouterFunction} bean rather than a branch inside {@link #internalCallbackRoutes} for the
     * same reason the two were never one route to begin with: they point at different backends now,
     * not just different paths.
     */
    @Bean
    RouterFunction<ServerResponse> providerSimRoutes(
        @Value("${paymesh.gateway.provider-sim-uri}") String providerSimUri
    ) {
        return route("paymesh-provider-sim")
            .route(path("/sim/**"), http())
            .before(uri(providerSimUri))
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
