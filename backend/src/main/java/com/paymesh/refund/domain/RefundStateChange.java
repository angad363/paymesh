package com.paymesh.refund.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One row of a refund's timeline.
 *
 * @param fromStatus null on creation -- there is no state before the first one
 * @param actorId null for PROVIDER and SYSTEM: a callback and a timer have nobody to name
 */
public record RefundStateChange(
    MerchantId merchantId,
    RefundId refundId,
    RefundStatus fromStatus,
    RefundStatus toStatus,
    ActorType actorType,
    String actorId,
    String reason,
    Instant occurredAt
) {

    /** Who caused the transition. Mirrors {@code OrderStateChange.ActorType}. */
    public enum ActorType {

        /** A person acting through the merchant API. */
        MERCHANT,

        /** A provider callback. */
        PROVIDER,

        /** A timer, a sweeper or an event consumer. */
        SYSTEM
    }

    public RefundStateChange {
        if (merchantId == null || refundId == null) {
            throw new IllegalArgumentException("A refund state change must identify its refund");
        }

        if (toStatus == null) {
            throw new IllegalArgumentException("A refund state change must have a target status");
        }

        if (actorType == null) {
            throw new IllegalArgumentException("A refund state change must have an actor type");
        }

        if (actorType != ActorType.MERCHANT && actorId != null) {
            throw new IllegalArgumentException(
                "Only a MERCHANT actor names a principal; " + actorType + " has nobody to name"
            );
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("A refund state change must have an instant");
        }
    }
}
