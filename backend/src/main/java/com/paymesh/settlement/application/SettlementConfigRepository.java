package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.SettlementConfig;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

public interface SettlementConfigRepository {

    /** Empty when the merchant has never set one. The caller supplies the platform default. */
    Optional<SettlementConfig> find(MerchantId merchantId);

    SettlementConfig save(SettlementConfig config);
}
