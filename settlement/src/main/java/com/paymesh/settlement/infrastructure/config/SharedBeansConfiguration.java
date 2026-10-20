package com.paymesh.settlement.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * The two platform beans the monolith keeps in {@code shared.infrastructure.SharedConfiguration}:
 * {@link Clock} and {@link TransactionTemplate} (ADR-010). Deliberately NOT here: the ApiKey filter
 * (no {@code api_credentials} access) and the merchant status filter (Settlement has no
 * merchant-status gate today, and ADR-047 section 6 adds none).
 */
@Configuration
public class SharedBeansConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
