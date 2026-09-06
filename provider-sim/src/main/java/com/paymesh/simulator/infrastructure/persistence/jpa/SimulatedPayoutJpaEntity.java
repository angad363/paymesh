package com.paymesh.simulator.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A row of {@code provider_payouts}. SDD 13.4. */
@Entity
@Table(name = "provider_payouts")
public class SimulatedPayoutJpaEntity {

    @Id
    @Column(name = "provider_payout_id", nullable = false, updatable = false, length = 60)
    private String providerPayoutId;

    @Column(name = "external_reference", nullable = false, updatable = false, length = 60)
    private String externalReference;

    @Column(name = "destination", nullable = false, updatable = false, length = 80)
    private String destination;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SimulatedPayoutJpaEntity() {
    }

    public SimulatedPayoutJpaEntity(
        String providerPayoutId,
        String externalReference,
        String destination,
        long amountMinor,
        String currency,
        String status,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.providerPayoutId = providerPayoutId;
        this.externalReference = externalReference;
        this.destination = destination;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.failureCode = failureCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    String providerPayoutId() {
        return providerPayoutId;
    }

    String externalReference() {
        return externalReference;
    }

    String destination() {
        return destination;
    }

    long amountMinor() {
        return amountMinor;
    }

    String currency() {
        return currency;
    }

    String status() {
        return status;
    }

    String failureCode() {
        return failureCode;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
