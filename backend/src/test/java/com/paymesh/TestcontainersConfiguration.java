package com.paymesh;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Throwaway PostgreSQL for tests that boot the Spring context.
 * <p>
 * {@code @ServiceConnection} overrides spring.datasource.* with the container's own URL and
 * credentials, so tests never touch the developer's local database and a fresh clone can build
 * with nothing installed but Docker. Flyway migrates the empty container on startup, which also
 * means every run re-proves the migrations from scratch.
 * <p>
 * Image tag tracks the PostgreSQL version this project runs against; keep it in step with the
 * database the app is deployed on, so schema behaviour under test matches production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:18-alpine");
    }
}
