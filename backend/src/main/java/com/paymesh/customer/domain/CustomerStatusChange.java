package com.paymesh.customer.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/** One row of a customer's lifecycle timeline. SDD 10.4's "auditable lifecycle". */
public record CustomerStatusChange(
    MerchantId merchantId,
    CustomerId customerId,
    CustomerStatus fromStatus,
    CustomerStatus toStatus,
    ActorType actorType,
    String actorId,
    String reason,
    Instant occurredAt
) {

    /**
     * MERCHANT, because blocking a buyer is the merchant's own commercial decision. SYSTEM is
     * reserved for the risk consumer SDD 10.5 anticipates ({@code risk.customer.blocked}), which
     * does not exist.
     */
    public enum ActorType {
        MERCHANT,
        SYSTEM
    }

    public CustomerStatusChange {
        if (merchantId == null || customerId == null) {
            throw new IllegalArgumentException("A customer status change must identify its customer");
        }

        if (toStatus == null) {
            throw new IllegalArgumentException("A customer status change must have a target status");
        }

        if (actorType == null) {
            throw new IllegalArgumentException("A customer status change must have an actor type");
        }

        if (actorType == ActorType.MERCHANT && (actorId == null || actorId.isBlank())) {
            throw new IllegalArgumentException("A MERCHANT status change must name the principal");
        }

        if (actorType == ActorType.SYSTEM && actorId != null) {
            throw new IllegalArgumentException("A SYSTEM status change has nobody to name");
        }

        reason = reason == null || reason.isBlank() ? null : reason.strip();

        if (occurredAt == null) {
            throw new IllegalArgumentException("A customer status change must have an instant");
        }
    }
}
