package com.paymesh.shared.outbox.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the outbox relay is tuned (ADR-016).
 * <p>
 * The {@code interval} is deliberately NOT bound here, matching {@code OrderExpiryProperties}:
 * Spring resolves {@code @Scheduled(fixedDelayString = "${...}")} from the environment directly, and
 * binding the same key into a record as well would give two places to read it from and one of them
 * would eventually be wrong. It is documented in {@code application.yaml} where the value lives.
 *
 * @param enabled   whether the timer bean is registered at all. Defaults on; the dev profile (which
 *                  the test suite runs under) turns it off, because a relay that moves orders to
 *                  PAID underneath an assertion is a flake generator
 * @param batchSize how many unpublished events one pass may take. Bounded so a backlog drains over
 *                  several passes instead of loading the whole log into memory, and so the number of
 *                  transactions one pass opens is predictable
 */
@Validated
@ConfigurationProperties("paymesh.events.outbox-relay")
public record OutboxRelayProperties(

    boolean enabled,

    @Min(1)
    int batchSize
) {
}
