package com.paymesh.settlement.infrastructure.persistence.jpa;

import com.paymesh.settlement.application.SettlementConfigRepository;
import com.paymesh.settlement.domain.SettlementConfig;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Duration;
import java.util.Optional;

/** PostgreSQL-backed SettlementConfigRepository. */
public final class JpaSettlementConfigRepository implements SettlementConfigRepository {

    private final SpringDataSettlementConfigRepository configs;

    public JpaSettlementConfigRepository(SpringDataSettlementConfigRepository configs) {
        this.configs = configs;
    }

    @Override
    public Optional<SettlementConfig> find(MerchantId merchantId) {
        return configs.findById(merchantId.value()).map(JpaSettlementConfigRepository::toDomain);
    }

    @Override
    public SettlementConfig save(SettlementConfig config) {
        // Seconds, not an INTERVAL: see V29. toSecondsPart would truncate, so this is the whole
        // duration in seconds and the column is sized for it.
        configs.save(new SettlementConfigJpaEntity(
            config.merchantId().value(),
            Math.toIntExact(config.holdingPeriod().toSeconds()),
            config.createdAt(),
            config.updatedAt()
        ));

        return config;
    }

    private static SettlementConfig toDomain(SettlementConfigJpaEntity entity) {
        return new SettlementConfig(
            MerchantId.from(entity.merchantId()),
            Duration.ofSeconds(entity.holdingPeriodSeconds()),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
