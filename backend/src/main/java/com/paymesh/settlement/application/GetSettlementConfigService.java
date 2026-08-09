package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.SettlementConfig;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A merchant's holding period, or the platform default when they have not set one.
 *
 * <h2>THE DEFAULT IS NOT PERSISTED ON READ</h2>
 *
 * A merchant with no row gets the default as a VALUE, not as a row written on their behalf. Writing
 * one would make "never configured" and "configured to exactly the default" indistinguishable, so a
 * later change to the platform default would silently skip every merchant who had been defaulted
 * into a row -- the opposite of what changing a default means.
 */
public final class GetSettlementConfigService {

    private final SettlementConfigRepository configs;
    private final Duration defaultHoldingPeriod;
    private final Clock clock;

    public GetSettlementConfigService(
        SettlementConfigRepository configs, Duration defaultHoldingPeriod, Clock clock
    ) {
        if (defaultHoldingPeriod == null || defaultHoldingPeriod.isNegative()) {
            throw new IllegalArgumentException(
                "Default holding period must be present and non-negative"
            );
        }

        this.configs = configs;
        this.defaultHoldingPeriod = defaultHoldingPeriod;
        this.clock = clock;
    }

    public SettlementConfig forMerchant(MerchantId merchantId) {
        return configs.find(merchantId).orElseGet(() -> {
            Instant now = Instant.now(clock);

            return new SettlementConfig(merchantId, defaultHoldingPeriod, now, now);
        });
    }

    /** Upsert. The merchant is the key, so there is no create-versus-update for a caller to get wrong. */
    public SettlementConfig set(MerchantId merchantId, Duration holdingPeriod) {
        Instant now = Instant.now(clock);

        return configs.save(configs.find(merchantId)
            .map(existing -> existing.withHoldingPeriod(holdingPeriod, now))
            .orElseGet(() -> new SettlementConfig(merchantId, holdingPeriod, now, now)));
    }
}
