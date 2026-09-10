package com.paymesh.notification.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the notification dispatcher is tuned. The {@code interval} is resolved straight into
 * {@code @Scheduled} from the environment rather than bound here -- two places to read one value is
 * how one of them drifts, the same note {@code WebhookDispatchProperties} carries.
 *
 * @param enabled whether the timer bean is registered at all. <b>Defaults on; the dev profile turns
 *     it off</b>, because the suite runs under dev and a timer flipping notification rows to SENT
 *     while a test asserts on them is a flake generator
 * @param batchSize how many PENDING notifications one pass may take, each in its own transaction
 * @param maxAttempts the attempt budget before a notification is FAILED rather than retried again --
 *     a terminal state, like ADR-025's dead letter, not an infinite retry
 */
@Validated
@ConfigurationProperties("paymesh.notification.dispatch")
public record NotificationDispatchProperties(

    boolean enabled,

    @Min(1)
    int batchSize,

    @Min(1)
    int maxAttempts
) {
}
