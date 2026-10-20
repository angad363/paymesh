package com.paymesh.settlement.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How PayMesh reaches the payout provider, and how it authenticates the answer.
 *
 * @param url where a payout is submitted. Configuration rather than something derived from the
 *     simulator's own settings, because a real acquirer is the point and the simulator is the
 *     stand-in -- nothing in Settlement knows it is talking to a module in the same process
 * @param apiKey the provider's key. Today the simulator's, which is why {@code dev} sets both to
 *     one value; it is a separate property so it can stop being one
 * @param callbackSecret the HMAC secret for {@code /internal/v1/payout-callbacks/**}. <b>Its own
 *     property</b>, not a read of the payment callback secret, for the reason ADR-019 gives about
 *     Refund's: shared today, and one property to change on the day they are not
 */
@Validated
@ConfigurationProperties("paymesh.settlement.payouts")
public record PayoutProperties(
    @NotBlank String url,
    @NotBlank String apiKey,
    @NotBlank String callbackSecret,
    Duration connectTimeout,
    Duration readTimeout
) {
}
