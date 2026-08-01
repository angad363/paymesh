package com.paymesh.payment.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The provider callback signing secret, bound and validated once at startup.
 * <p>
 * FAIL-CLOSED, EXACTLY LIKE THE JWT SECRET, and for a sharper reason. This secret is the ONLY
 * authentication on an endpoint that moves payments to SUCCEEDED. Absent, the application must not
 * start; set to the value committed to this repository outside development, it must not start
 * either -- see {@code DevelopmentSecretGuard}. An HMAC verified against a public string
 * authenticates nobody, and the failure is silent: every forged callback verifies.
 * <p>
 * One secret and one provider. Per-provider secrets and rotation are deliberately deferred (design
 * section 4.7); the shape that would need is a map keyed by provider name, and building it now would
 * be a guess about a provider that does not exist.
 */
@Validated
@ConfigurationProperties("paymesh.provider")
public record ProviderProperties(

    @NotBlank
    String callbackSecret
) {
}
