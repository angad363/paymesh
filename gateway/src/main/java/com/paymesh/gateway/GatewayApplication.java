package com.paymesh.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The PayMesh API gateway (ADR-040): one north-south entry point in front of the monolith, doing
 * edge authentication, routing and rate limiting.
 *
 * <p>It owns no data and no business rule. Every request it accepts it forwards, unchanged, to the
 * monolith on {@code paymesh.gateway.backend-uri}; the only requests it stops are ones that fail
 * authentication or exceed a rate limit, and it stops those at the edge so they never reach a
 * service. When services split in 3B, the routes re-point per service and nothing else here changes
 * -- which is the whole reason the front door goes up first.
 *
 * <p>It is optional until then: a client can still address the monolith directly on 8080. The
 * gateway listens on its own port (8081) so both can run side by side during the cutover.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
