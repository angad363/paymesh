package com.paymesh.refund.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param callbackSecret the HMAC secret for {@code /internal/v1/refund-callbacks/**}.
 *     <p>
 *     ITS OWN PROPERTY, not a read of {@code paymesh.provider.callback-secret}, even though dev
 *     supplies the same value to both. Two names is what makes them separable: the day refunds move
 *     to a different provider, or the payment secret is rotated after a leak, this one changes
 *     independently and neither route is touched by the other's incident. Sharing one property
 *     would make that a code change rather than a config change.
 *     <p>
 *     Like the others, {@code DevelopmentSecretGuard} refuses the committed value unless the dev
 *     profile is the sole active one -- this key decides that money went back to a customer.
 */
@Validated
@ConfigurationProperties("paymesh.refund")
public record RefundProperties(

    @NotBlank
    String callbackSecret
) {
}
