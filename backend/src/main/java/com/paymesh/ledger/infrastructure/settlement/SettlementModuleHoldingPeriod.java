package com.paymesh.ledger.infrastructure.settlement;

import com.paymesh.ledger.application.HoldingPeriodPolicy;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Duration;

/**
 * The Ledger's {@link HoldingPeriodPolicy}, answered by the Settlement module.
 * <p>
 * The single allowed crossing, in the consumer's infrastructure, exactly like
 * {@code OrderModuleLookup}. {@code ModuleBoundaryTest} names this one file and refuses any other.
 */
public final class SettlementModuleHoldingPeriod implements HoldingPeriodPolicy {

    private final GetSettlementConfigService settlementConfigs;

    public SettlementModuleHoldingPeriod(GetSettlementConfigService settlementConfigs) {
        this.settlementConfigs = settlementConfigs;
    }

    @Override
    public Duration forMerchant(MerchantId merchantId) {
        return settlementConfigs.forMerchant(merchantId).holdingPeriod();
    }
}
