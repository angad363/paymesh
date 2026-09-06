package com.paymesh.simulator.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * The two platform beans every application service here is wired against: {@link Clock} (so tests
 * stay deterministic instead of reading {@code Instant.now()}) and {@link TransactionTemplate} (the
 * only way this codebase opens a multi-statement transaction, per ADR-010 -- application services
 * are final classes with no interface, so {@code @Transactional} cannot proxy them).
 * <p>
 * In the monolith these live in {@code shared.infrastructure.SharedConfiguration}, one definition
 * shared by every capability. This module has exactly one capability, so the shared bean and the
 * capability's own beans would otherwise live in the same file for no reason other than history;
 * kept separate anyway, matching the monolith's shape, so a second capability arriving here later
 * (it will not, by design) would not have to unpick them.
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
