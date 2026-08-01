package com.paymesh.shared.outbox.infrastructure.config;

import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.infrastructure.persistence.jpa.JpaOutboxWriter;
import com.paymesh.shared.outbox.infrastructure.persistence.jpa.SpringDataOutboxRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit wiring for the outbox (no component scanning of application classes).
 * <p>
 * The outbox lives in {@code shared} for the same reason the idempotency layer does: it governs
 * every capability and belongs to none. Built inside a feature package it would be platform code
 * shaped to one caller.
 */
@Configuration
public class OutboxConfiguration {

    @Bean
    OutboxWriter outboxWriter(SpringDataOutboxRepository springDataOutboxRepository) {
        return new JpaOutboxWriter(springDataOutboxRepository);
    }
}
