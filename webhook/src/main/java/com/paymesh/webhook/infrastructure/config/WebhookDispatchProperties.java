package com.paymesh.webhook.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the delivery dispatcher is tuned. The {@code interval} is deliberately not bound here -- it is
 * resolved straight into {@code @Scheduled} from the environment, and two places to read one value
 * means one of them eventually drifts. Same note as {@code SimulatorDispatchProperties}.
 *
 * @param enabled whether the timer bean is registered at all. <b>Defaults on; the dev profile turns
 *     it off</b>, because the test suite runs under dev and a dispatcher POSTing at merchants while
 *     a test asserts on a delivery row is a flake generator
 * @param batchSize how many due deliveries one pass may take. Each is its own transaction and its
 *     own socket, so this bounds sockets rather than lock duration
 * @param connectTimeout how long to wait for the merchant's TCP and TLS handshake
 * @param readTimeout how long to wait for the merchant's response. <b>Short on purpose:</b> the HTTP
 *     call happens inside the delivery row's transaction, so this is also the longest a hung
 *     merchant can hold one row lock and one database connection
 */
@Validated
@ConfigurationProperties("paymesh.webhook.dispatch")
public record WebhookDispatchProperties(

    boolean enabled,

    @Min(1)
    int batchSize,

    Duration connectTimeout,

    Duration readTimeout
) {
}
