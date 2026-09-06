package com.paymesh.simulator.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistence model for {@code provider_outbound_callbacks} (V13__create_provider_simulator.sql).
 * Column definitions must match that migration -- {@code ddl-auto=validate} fails startup on drift.
 * <p>
 * <b>{@code body} is a String mapped to TEXT, and that is load-bearing.</b> A JSONB round trip
 * normalises key order and whitespace, so the bytes read back would not be the bytes that were
 * written -- and the HMAC covers bytes. Holding the payload as an opaque string means the row IS the
 * payload: the dispatcher signs this value and posts this value, with no serialization step in
 * between for the two to drift across.
 * <p>
 * Unlike {@code ProviderCallbackJpaEntity} on the inbound side, this one does <b>not</b> implement
 * {@code Persistable}. That class forces {@code persist} so a duplicate insert collides, because
 * there the collision is the deduplication signal. Here rows are read back and updated on every
 * delivery attempt, so merge semantics are exactly what is wanted.
 */
@Entity
@Table(name = "provider_outbound_callbacks")
public class OutboundCallbackJpaEntity {

    @Id
    @Column(name = "outbound_callback_id", nullable = false, length = 60)
    private String outboundCallbackId;

    // DELIBERATELY NOT UNIQUE -- two rows sharing this value IS the duplicate-callback scenario.
    // The uniqueness that matters lives on the receiving side (pk_provider_callbacks, V10).
    @Column(name = "external_event_id", nullable = false, length = 120)
    private String externalEventId;

    /** NULLABLE since V31: a payout callback names a payout instead. The XOR is a CHECK. */
    @Column(name = "provider_payment_id", length = 50)
    private String providerPaymentId;

    @Column(name = "provider_payout_id", length = 60)
    private String providerPayoutId;

    @Column(name = "callback_target", nullable = false, length = 20)
    private String callbackTarget;

    @Column(name = "callback_reference", nullable = false, length = 60)
    private String callbackReference;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    // The PROVIDER's event clock, which PayMesh's ordering guard compares. NOT the signature's
    // freshness stamp, which is taken at delivery time.
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "deliver_after", nullable = false)
    private Instant deliverAfter;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_response_status")
    private Integer lastResponseStatus;

    @Column(name = "last_response_outcome", length = 32)
    private String lastResponseOutcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected OutboundCallbackJpaEntity() {
    }

    OutboundCallbackJpaEntity(
        String outboundCallbackId,
        String externalEventId,
        String providerPaymentId,
        String providerPayoutId,
        String callbackTarget,
        String callbackReference,
        String outcome,
        Instant occurredAt,
        Instant deliverAfter,
        String body,
        String status,
        int attempts,
        Instant lastAttemptAt,
        Integer lastResponseStatus,
        String lastResponseOutcome,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.outboundCallbackId = outboundCallbackId;
        this.externalEventId = externalEventId;
        this.providerPaymentId = providerPaymentId;
        this.providerPayoutId = providerPayoutId;
        this.callbackTarget = callbackTarget;
        this.callbackReference = callbackReference;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
        this.deliverAfter = deliverAfter;
        this.body = body;
        this.status = status;
        this.attempts = attempts;
        this.lastAttemptAt = lastAttemptAt;
        this.lastResponseStatus = lastResponseStatus;
        this.lastResponseOutcome = lastResponseOutcome;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    String outboundCallbackId() {
        return outboundCallbackId;
    }

    String externalEventId() {
        return externalEventId;
    }

    String providerPaymentId() {
        return providerPaymentId;
    }

    String providerPayoutId() {
        return providerPayoutId;
    }

    String callbackTarget() {
        return callbackTarget;
    }

    String callbackReference() {
        return callbackReference;
    }

    String outcome() {
        return outcome;
    }

    Instant occurredAt() {
        return occurredAt;
    }

    Instant deliverAfter() {
        return deliverAfter;
    }

    String body() {
        return body;
    }

    String status() {
        return status;
    }

    int attempts() {
        return attempts;
    }

    Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    Integer lastResponseStatus() {
        return lastResponseStatus;
    }

    String lastResponseOutcome() {
        return lastResponseOutcome;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
