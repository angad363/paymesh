package com.paymesh.shared.outbox.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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
 * @param maxAttempts how many failed deliveries an event gets before the relay gives up on it and
 *                  dead-letters it (ADR-025). {@code @Min(1)} rather than allowing 0: a budget of
 *                  zero would abandon every event on its first transient failure. There is
 *                  deliberately NO value meaning "retry forever" -- that was the old behaviour and
 *                  it is the bug
 * @param backlogAlertAge how old the oldest undelivered event may get before
 *                  {@code OutboxBacklogHealthIndicator} reports the relay unhealthy. This is SDD
 *                  section 24's "oldest unpublished event age" threshold. Must be comfortably
 *                  larger than {@code interval} times {@code maxAttempts}, or a burst that the relay
 *                  is successfully working through would trip it
 */
@Validated
@ConfigurationProperties("paymesh.events.outbox-relay")
public record OutboxRelayProperties(

    boolean enabled,

    @Min(1)
    int batchSize,

    @Min(1)
    int maxAttempts,

    @NotNull
    Duration backlogAlertAge
) {
}
