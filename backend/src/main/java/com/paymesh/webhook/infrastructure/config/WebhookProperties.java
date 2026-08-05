package com.paymesh.webhook.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param masterKey THE ONE SECRET THIS CAPABILITY HAS. Every endpoint's signing secret is derived
 *     from it (ADR-028 §2), so nothing in the database is sensitive and there is no ciphertext
 *     column -- but it also means a leak of this value forges PayMesh's signature to every merchant
 *     at once. {@code WebhookSecrets} refuses anything under 32 bytes and
 *     {@code DevelopmentSecretGuard} refuses the committed development value outside {@code dev}.
 *     <p>
 *     <b>Rotating it invalidates every merchant's secret simultaneously</b>, which per-endpoint
 *     rotation exists precisely to avoid. It is a break-glass action, not maintenance.
 * @param allowPrivateAddresses whether a webhook may be POSTed to a loopback or private address.
 *     <b>A development switch and nothing else</b>: it disables the SSRF guard. Honoured only when
 *     {@code dev} is the single active profile -- see {@code WebhookConfiguration}.
 */
@Validated
@ConfigurationProperties("paymesh.webhook")
public record WebhookProperties(

    @NotBlank
    String masterKey,

    boolean allowPrivateAddresses
) {
}
