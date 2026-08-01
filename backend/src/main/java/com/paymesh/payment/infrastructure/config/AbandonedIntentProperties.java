package com.paymesh.payment.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How long a checkout stays open before the platform releases the order it is holding.
 * <p>
 * A sibling of {@link ProcessingTimeoutProperties} in shape and nothing else. <b>That one is a money
 * decision; this one is a product decision.</b> Timing out a PROCESSING intent can record a real
 * payment as failed, which is why its age is an hour and why lowering it needs a reconciliation job.
 * The states this age governs precede a provider being told anything, so no money can be in flight
 * and the only thing a short age costs is a customer who wandered off and came back finding their
 * checkout closed.
 * <p>
 * {@code interval} is deliberately absent as a bound field, exactly as it is there: the scheduler
 * reads the raw property, and binding it here as well would give two places to read one value from.
 *
 * @param age       how long an intent may sit unconfirmed before it is cancelled. Long enough that a
 *                  customer fetching their card is never interrupted; short enough that an order is
 *                  not held for a day by someone who closed the tab
 * @param batchSize how many abandoned intents one sweep may take, so a backlog drains over several
 *                  runs instead of loading an unbounded set into memory
 */
@Validated
@ConfigurationProperties("paymesh.payments.abandoned-intents")
public record AbandonedIntentProperties(

    boolean enabled,

    @NotNull
    Duration age,

    @Min(1)
    int batchSize
) {
}
