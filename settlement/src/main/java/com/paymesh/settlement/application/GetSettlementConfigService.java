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

            // NO DESTINATION AND THE SMALLEST MINIMUM. A default payout destination cannot exist --
            // there is no bank account to guess -- so an unconfigured merchant is simply never
            // batched, which is the safe direction to fail in.
            return new SettlementConfig(merchantId, defaultHoldingPeriod, null, 1L, now, now);
        });
    }

    /**
     * Upsert. The merchant is the key, so there is no create-versus-update for a caller to get
     * wrong, and PUT semantics: every setting is replaced rather than merged.
     */
    /**
     * Set only the holding period, leaving the payout destination and minimum at their current
     * values (or the defaults for a merchant with no row). The release path cares about the holding
     * period alone, so this is what it calls; the full setter is the API's.
     */
    public SettlementConfig set(MerchantId merchantId, Duration holdingPeriod) {
        return configs.find(merchantId)
            .map(existing -> configs.save(existing.with(
                holdingPeriod, existing.payoutDestination(), existing.minimumPayoutMinor(),
                Instant.now(clock)
            )))
            .orElseGet(() -> {
                Instant now = Instant.now(clock);

                return configs.save(new SettlementConfig(
                    merchantId, holdingPeriod, null, 1L, now, now
                ));
            });
    }

    public SettlementConfig set(
        MerchantId merchantId,
        Duration holdingPeriod,
        String payoutDestination,
        long minimumPayoutMinor
    ) {
        Instant now = Instant.now(clock);

        return configs.save(configs.find(merchantId)
            .map(existing -> existing.with(
                holdingPeriod, payoutDestination, minimumPayoutMinor, now
            ))
            .orElseGet(() -> new SettlementConfig(
                merchantId, holdingPeriod, payoutDestination, minimumPayoutMinor, now, now
            )));
    }
}
