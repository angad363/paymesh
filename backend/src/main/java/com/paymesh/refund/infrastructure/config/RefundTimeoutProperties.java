package com.paymesh.refund.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param age how long a refund may sit in PROCESSING before it is given up on. Deliberately longer
 *     than Payment's equivalent: a lost refund callback costs the merchant head-room, while a
 *     premature timeout risks believing money did not go back when it did.
 */
@Validated
@ConfigurationProperties("paymesh.refunds.processing-timeout")
public record RefundTimeoutProperties(

    boolean enabled,

    @NotNull
    Duration age,

    @NotNull
    Duration interval,

    @Min(1)
    int batchSize
) {
}
