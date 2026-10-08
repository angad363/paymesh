package com.paymesh;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Throwaway PostgreSQL for this module's context-loading tests. Identical in shape and package to
 * the monolith's own {@code com.paymesh.TestcontainersConfiguration} and to {@code provider-sim}'s
 * (ADR-041) -- carried over unchanged so the webhook capability's own tests needed no import edit
 * when they moved (ADR-042). {@code @ServiceConnection} overrides {@code spring.datasource.*} with
 * the container's own URL and superuser credentials, so this module's own Flyway history (the
 * {@code webhook} schema, adopted at V1) starts fresh every run and never touches the fenced
 * {@code webhook_svc} role or its grants.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:18-alpine");
    }
}
