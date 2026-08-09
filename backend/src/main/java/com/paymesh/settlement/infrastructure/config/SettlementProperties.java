package com.paymesh.settlement.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param defaultHoldingPeriod applied to a merchant who has never set one. Supplied as a VALUE
 *     rather than written into a row on their behalf -- see {@code GetSettlementConfigService} for
 *     why persisting a default makes a later change to it silently skip everyone.
 */
@Validated
@ConfigurationProperties("paymesh.settlement")
public record SettlementProperties(Duration defaultHoldingPeriod) {
}
