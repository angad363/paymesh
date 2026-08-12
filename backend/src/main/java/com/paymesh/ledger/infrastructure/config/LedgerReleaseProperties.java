package com.paymesh.ledger.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled whether the release timer bean is registered. Off under {@code dev} like every
 *     other sweep here: the suite runs on that profile and a timer moving balances mid-assertion is
 *     a flake generator. The service is an ordinary bean regardless, so tests call it directly.
 * @param batchSize how many unreleased captures one pass may take. Each is its own transaction.
 */
@Validated
@ConfigurationProperties("paymesh.ledger.release")
public record LedgerReleaseProperties(boolean enabled, @Min(1) int batchSize) {
}
