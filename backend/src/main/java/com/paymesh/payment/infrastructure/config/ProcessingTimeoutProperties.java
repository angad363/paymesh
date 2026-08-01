package com.paymesh.payment.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the PROCESSING timeout is tuned (ADR-015).
 * <p>
 * The {@code interval} -- how often the sweep runs -- is deliberately NOT bound here. Spring
 * resolves {@code @Scheduled(fixedDelayString = "${...}")} from the environment directly, and
 * binding the same key into a record as well would give two places to read it from and one of them
 * would eventually be wrong. It is documented in {@code application.yaml} where the value lives.
 * <p>
 * {@code age} is a different thing and IS bound here, because the service applies it: it is how long
 * an intent may sit in PROCESSING before the platform gives up on it. <b>The two must not be
 * confused.</b> Shortening the interval makes the timeout more punctual; shortening the age makes it
 * more likely to be wrong about a payment that succeeded.
 *
 * @param enabled   whether the timer bean is registered at all. Defaults on; the dev profile (which
 *                  the test suite runs under) turns it off
 * @param age       how long an intent may sit in PROCESSING before it is failed. <b>Generous on
 *                  purpose.</b> Every hour of it is an hour a lost callback strands an order, and
 *                  every hour cut off it is a larger chance of recording a real payment as failed --
 *                  and only the second of those can take money from a customer with nothing on this
 *                  side to show for it. ADR-015 section 4
 * @param batchSize how many stranded intents one sweep may take, so a backlog drains over several
 *                  runs instead of loading an unbounded set into memory
 */
@Validated
@ConfigurationProperties("paymesh.payments.processing-timeout")
public record ProcessingTimeoutProperties(

    boolean enabled,

    @NotNull
    Duration age,

    @Min(1)
    int batchSize
) {
}
