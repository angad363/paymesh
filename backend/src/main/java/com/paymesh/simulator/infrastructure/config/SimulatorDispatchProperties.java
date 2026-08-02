package com.paymesh.simulator.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the callback dispatcher is tuned.
 * <p>
 * The {@code interval} is deliberately NOT bound here. Spring resolves
 * {@code @Scheduled(fixedDelayString = "${...}")} from the environment directly, and binding the
 * same key into a record as well would give two places to read it from and one of them would
 * eventually be wrong. It is documented in {@code application.yaml} where the value lives -- the same
 * reasoning {@code OrderExpiryProperties} records.
 *
 * @param enabled     whether the timer bean is registered at all. <b>Defaults on; the dev profile
 *                    (which the test suite runs under) turns it off</b>, because a timer posting
 *                    callbacks at PayMesh while a test asserts on a payment intent is a flake
 *                    generator
 * @param batchSize   how many due callbacks one pass may take. Each is its own transaction, so this
 *                    is not a lock-duration multiplier -- it bounds how many sockets one pass opens
 * @param maxAttempts how many times one callback may be delivered before it is ABANDONED. A real
 *                    provider's retry budget is finite; unbounded retries against a 401 would be an
 *                    infinite loop wearing the costume of resilience
 * @param retryDelay  how far {@code deliver_after} is pushed out after a failed attempt
 * @param readTimeout how long to wait for PayMesh to answer. <b>Short on purpose:</b> the HTTP call
 *                    happens inside the row's transaction, so this is also how long a hung receiver
 *                    can hold one row lock and one database connection
 */
@Validated
@ConfigurationProperties("paymesh.simulator.dispatch")
public record SimulatorDispatchProperties(

    boolean enabled,

    @Min(1)
    int batchSize,

    @Min(1)
    int maxAttempts,

    Duration retryDelay,

    Duration readTimeout
) {
}
