package com.paymesh.order.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the order expiry sweep is tuned (ADR-014).
 * <p>
 * The {@code interval} is deliberately NOT bound here. Spring resolves
 * {@code @Scheduled(fixedDelayString = "${...}")} from the environment directly, and binding the
 * same key into a record as well would give two places to read it from and one of them would
 * eventually be wrong. It is documented in {@code application.yaml} where the value lives.
 *
 * @param enabled   whether the timer bean is registered at all. Defaults on; the dev profile (which
 *                  the test suite runs under) turns it off
 * @param batchSize how many expirable orders one sweep may take. Bounded so a backlog drains over
 *                  several runs instead of loading the whole platform into memory, and so the sweep
 *                  holds a predictable number of short row locks rather than an unbounded number
 */
@Validated
@ConfigurationProperties("paymesh.orders.expiry-sweep")
public record OrderExpiryProperties(

    boolean enabled,

    @Min(1)
    int batchSize
) {
}
