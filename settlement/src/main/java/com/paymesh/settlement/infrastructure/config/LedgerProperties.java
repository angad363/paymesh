package com.paymesh.settlement.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Where Settlement reads the ledger from (ADR-047 section 1a).
 *
 * @param mode only {@code http} exists in this deployable: the in-process adapter needs the ledger's
 *     classes, which live in the monolith. Kept as a property so the topology reads the same as the
 *     monolith's {@code in-process}/{@code http} switch (ADR-044's seam)
 * @param url the ledger read host, no path
 * @param key the shared key sent as {@code X-PayMesh-Ledger-Key}; {@code @NotBlank} so a deployment
 *     that forgets it fails at startup instead of failing closed on every tick
 */
@Validated
@ConfigurationProperties("paymesh.settlement.ledger")
public record LedgerProperties(
    String mode,
    @NotBlank String url,
    @NotBlank String key,
    Duration connectTimeout,
    Duration readTimeout
) {
}
