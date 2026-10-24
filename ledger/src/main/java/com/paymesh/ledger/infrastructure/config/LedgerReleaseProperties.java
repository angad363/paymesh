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
 * @param defaultHoldingPeriod ponytail: every merchant's holding period in this service. The
 *     per-merchant override lives in settlement's schema, which ledger_svc cannot read; honoring
 *     it needs a settlement-owned read, so the flag stays off until that exists (ADR-050 risk).
 * @param batchSize how many unreleased captures one pass may take. Each is its own transaction.
 */
@Validated
@ConfigurationProperties("paymesh.ledger.release")
public record LedgerReleaseProperties(
    boolean enabled, @Min(1) int batchSize, @NotNull Duration defaultHoldingPeriod
) {
}
