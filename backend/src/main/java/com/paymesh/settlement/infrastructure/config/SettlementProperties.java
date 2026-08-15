package com.paymesh.settlement.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param defaultHoldingPeriod applied to a merchant who has never set one. Supplied as a VALUE
 *     rather than written into a row on their behalf -- see {@code GetSettlementConfigService} for
 *     why persisting a default makes a later change to it silently skip everyone.
 * @param cutBatchSize how many payouts one submission pass takes. Bounds work per pass rather than
 *     lock duration -- each payout is submitted in its own transaction
 * @param payoutRetryDelay how long a refused submission waits. Fixed rather than exponential: a
 *     bank that would not take a transfer is not a bank being rate-limited
 * @param answerTimeout how long a SUBMITTED payout waits for a callback before becoming due again.
 *     Resubmission is safe -- the provider deduplicates on PayMesh's payout id -- so this is what
 *     stops a lost callback stranding a merchant's money in the in-transit account forever
 */
@Validated
@ConfigurationProperties("paymesh.settlement")
public record SettlementProperties(
    Duration defaultHoldingPeriod,
    int cutBatchSize,
    Duration payoutRetryDelay,
    Duration answerTimeout
) {
}
