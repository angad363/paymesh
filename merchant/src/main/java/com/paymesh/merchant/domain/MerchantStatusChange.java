package com.paymesh.merchant.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One row of a merchant's lifecycle timeline.
 *
 * <h2>THERE IS NO {@code MERCHANT} ACTOR, AND THAT IS THE CONTROL</h2>
 *
 * {@code OrderStateChange} and {@code RefundStateChange} both permit a MERCHANT actor, because a
 * merchant cancelling their own order is ordinary. A merchant lifting their own suspension is not:
 * it would make the suspension advisory. Only PLATFORM and SYSTEM exist here, and
 * {@code ck_merchant_status_history_actor} refuses anything else at the schema too.
 *
 * @param actorId the operator's user id. Required for PLATFORM, forbidden for SYSTEM -- a timer has
 *     nobody to name, and recording one in an audit table would be a lie.
 * @param reason required on SUSPENDED and CLOSED. "We stopped this business taking money" with no
 *     reason recorded is not an audit trail.
 */
public record MerchantStatusChange(
    MerchantId merchantId,
    MerchantStatus fromStatus,
    MerchantStatus toStatus,
    ActorType actorType,
    String actorId,
    String reason,
    Instant occurredAt
) {

    /** Who caused the transition. */
    public enum ActorType {

        /** A platform operator acting through the admin API. */
        PLATFORM,

        /** A migration, a timer, or an automated decision. */
        SYSTEM
    }

    public MerchantStatusChange {
        if (merchantId == null) {
            throw new IllegalArgumentException("A merchant status change must identify its merchant");
        }

        if (toStatus == null) {
            throw new IllegalArgumentException("A merchant status change must have a target status");
        }

        if (actorType == null) {
            throw new IllegalArgumentException("A merchant status change must have an actor type");
        }

        if (actorType == ActorType.PLATFORM && (actorId == null || actorId.isBlank())) {
            throw new IllegalArgumentException("A PLATFORM status change must name the operator");
        }

        if (actorType == ActorType.SYSTEM && actorId != null) {
            throw new IllegalArgumentException("A SYSTEM status change has nobody to name");
        }

        boolean stopsTrading =
            toStatus == MerchantStatus.SUSPENDED || toStatus == MerchantStatus.CLOSED;

        if (stopsTrading && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                "Suspending or closing a merchant requires a stated reason"
            );
        }

        reason = reason == null || reason.isBlank() ? null : reason.strip();

        if (occurredAt == null) {
            throw new IllegalArgumentException("A merchant status change must have an instant");
        }
    }
}
