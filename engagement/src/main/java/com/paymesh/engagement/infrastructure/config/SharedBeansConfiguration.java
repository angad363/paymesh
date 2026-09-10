package com.paymesh.engagement.infrastructure.config;

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
 * shared by every capability. This module has three capabilities (Notification, Reporting, Audit)
 * and none of them owns a cross-cutting bean like this one, so it lives at the module root under
 * {@code com.paymesh.engagement}, the same split {@code webhook} and {@code provider-sim} made for
 * their own single capability (ADR-041, ADR-042).
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
