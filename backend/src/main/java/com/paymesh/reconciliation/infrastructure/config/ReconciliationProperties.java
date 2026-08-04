package com.paymesh.reconciliation.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How reconciliation is tuned and where it fetches from (ADR-026).
 * <p>
 * {@code interval} and {@code initial-delay} are deliberately NOT bound here, matching
 * {@code OutboxRelayProperties} and {@code OrderExpiryProperties}: Spring resolves
 * {@code @Scheduled(fixedDelayString = "${...}")} straight from the environment, and binding the
 * same key into a record as well would give two places to read it from and one of them would
 * eventually be wrong.
 *
 * @param enabled      whether the timer bean is registered at all. The dev profile turns it off --
 *                     that profile is what the test suite runs under, and a job that repairs
 *                     payments underneath an assertion is a flake generator
 * @param baseUrl      where the provider's API lives. Points at this application's own port while
 *                     the provider is the bundled simulator, which is what makes the HTTP hop a
 *                     loopback call rather than an external dependency
 * @param provider     the provider's name, and half of the callback deduplication key. MUST match
 *                     the name real callbacks arrive under, or a reconciled event and a real one
 *                     would occupy different dedup namespaces and each could be applied once
 * @param apiKeyHeader the header {@code SimulatorApiKeyFilter} reads
 * @param apiKey       the value it expects. Configuration, not a secret this module mints
 * @param lookbackDays how many UTC days back each pass covers, today included. More than one
 *                     because a callback can be late by more than a day and because a pass that
 *                     does not run must not skip its day forever
 * @param timeoutMs    how long to wait on the provider. Bounded so a hung provider costs one pass
 *                     rather than the scheduler thread
 */
@Validated
@ConfigurationProperties("paymesh.reconciliation")
public record ReconciliationProperties(

    boolean enabled,

    @NotBlank
    String baseUrl,

    @NotBlank
    String provider,

    @NotBlank
    String apiKeyHeader,

    @NotBlank
    String apiKey,

    @Min(1)
    int lookbackDays,

    @Min(1)
    int timeoutMs
) {
}
