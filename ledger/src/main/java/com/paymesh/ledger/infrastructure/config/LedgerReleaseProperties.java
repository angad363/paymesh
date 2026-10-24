package com.paymesh.ledger.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param enabled THE SINGLE SWITCH of exclusive ownership of the release job (ADR-050 section 5):
 *     true in exactly one process. DEFAULT FALSE in the ledger service so a stock start cannot run
 *     it beside the monolith -- two releases would credit available twice. The service is an
 *     ordinary bean regardless, so tests call it directly.
 * @param defaultHoldingPeriod the FALLBACK for a merchant with no projected {@code
 *     settlement_config_ref} row, returned as a value (ADR-051). Must equal settlement's platform
 *     default (pinned by a test).
 * @param batchSize how many unreleased captures one pass may take. Each is its own transaction.
 */
@Validated
@ConfigurationProperties("paymesh.ledger.release")
public record LedgerReleaseProperties(
    boolean enabled, @Min(1) int batchSize, @NotNull Duration defaultHoldingPeriod
) {
}
