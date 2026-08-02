package com.paymesh.simulator.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The simulator's own credential and where it calls PayMesh back.
 * <p>
 * FAIL-CLOSED ON THE KEY, exactly like the JWT and callback secrets. Absent, the application must
 * not start; set to the value committed to this repository outside development, it must not start
 * either ({@code DevelopmentSecretGuard}). {@code POST /sim/v1/payments} queues a callback that will
 * mark a payment SUCCEEDED, so an unauthenticated simulator is an unauthenticated way to collect any
 * payment on the platform.
 *
 * @param apiKey      the shared key {@code SimulatorApiKeyFilter} checks on {@code /sim/v1/**}
 * @param callbackUrl where signed callbacks are POSTed. Configurable rather than derived because the
 *                    simulator is built to be independently deployable (SDD 13.6) -- a URL computed
 *                    from local settings would only ever be able to reach a PayMesh in the same
 *                    process, which is precisely the coupling this module is designed without
 */
@Validated
@ConfigurationProperties("paymesh.simulator")
public record SimulatorProperties(

    @NotBlank
    String apiKey,

    @NotBlank
    String callbackUrl
) {
}
