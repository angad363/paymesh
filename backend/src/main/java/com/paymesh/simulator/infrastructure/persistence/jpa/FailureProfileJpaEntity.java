package com.paymesh.simulator.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistence model for {@code provider_failure_profile} (V13__create_provider_simulator.sql).
 * <p>
 * One row, and {@code ck_provider_failure_profile_singleton} is what enforces that -- not this class
 * and not the service. V13 seeds it, so its absence is a corrupt database rather than a case to
 * handle.
 */
@Entity
@Table(name = "provider_failure_profile")
public class FailureProfileJpaEntity {

    @Id
    @Column(name = "profile_id", nullable = false, length = 20)
    private String profileId;

    @Column(name = "default_behaviour", nullable = false, length = 30)
    private String defaultBehaviour;

    @Column(name = "callback_delay_ms", nullable = false)
    private int callbackDelayMs;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected FailureProfileJpaEntity() {
    }

    FailureProfileJpaEntity(
        String profileId,
        String defaultBehaviour,
        int callbackDelayMs,
        Instant updatedAt
    ) {
        this.profileId = profileId;
        this.defaultBehaviour = defaultBehaviour;
        this.callbackDelayMs = callbackDelayMs;
        this.updatedAt = updatedAt;
    }

    String defaultBehaviour() {
        return defaultBehaviour;
    }

    int callbackDelayMs() {
        return callbackDelayMs;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
