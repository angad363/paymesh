package com.paymesh.settlement.infrastructure.config;

import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.settlement.application.SettlementConfigRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.JpaSettlementConfigRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.SpringDataSettlementConfigRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Settlement's beans, wired by hand (ADR-002). */
@Configuration
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementConfiguration {

    @Bean
    SettlementConfigRepository settlementConfigRepository(
        SpringDataSettlementConfigRepository configs
    ) {
        return new JpaSettlementConfigRepository(configs);
    }

    @Bean
    GetSettlementConfigService getSettlementConfigService(
        SettlementConfigRepository configs, SettlementProperties properties, Clock clock
    ) {
        return new GetSettlementConfigService(configs, properties.defaultHoldingPeriod(), clock);
    }
}
