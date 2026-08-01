package com.paymesh.order.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One row of an order's timeline: it was in {@code fromStatus}, something moved it to
 * {@code toStatus}, and this is who and why.
 * <p>
 * Append-only. There is no update and no delete, here or on the repository port -- a history that
 * can be rewritten is not a history.
 * <p>
 * Deliberately a near-copy of {@code PaymentStateChange} rather than a shared type. The two records
 * carry different identifier types and different actor vocabularies, and hoisting them into
 * {@code shared} would make one generic carrier that every capability's history has to agree on
 * forever -- a coupling the module boundaries exist to avoid. The duplication is nine fields.
 *
 * @param fromStatus null for the creation row. An order that has just been created came from
 *                   nowhere, and repeating PENDING in both columns would claim a transition that
 *                   never happened.
 * @param actorId    which merchant, when there is one to name. SYSTEM actions have no principal.
 */
public record OrderStateChange(
    MerchantId merchantId,
    OrderId orderId,
    OrderStatus fromStatus,
    OrderStatus toStatus,
    ActorType actorType,
    String actorId,
    String reason,
    Instant occurredAt
) {

    public OrderStateChange {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        if (orderId == null) {
            throw new IllegalArgumentException("Order Identifier cannot be null");
        }

        if (toStatus == null) {
            throw new IllegalArgumentException("Target status cannot be null");
        }

        if (actorType == null) {
            throw new IllegalArgumentException("Actor type cannot be null");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("Transition timestamp cannot be null");
        }
    }

    /**
     * Who caused a transition. Mirrors {@code ck_order_state_history_actor}.
     * <p>
     * TWO VALUES, NOT PAYMENT'S THREE. There is no PROVIDER here: a provider callback never touches
     * an order, because Payment does not write the {@code orders} table at all (design spec 0.5).
     * When {@code orders.status} eventually moves on a successful payment it will be an Order-owned
     * consumer of {@code payment.succeeded} doing it, which is SYSTEM.
     */
    public enum ActorType {
        MERCHANT,
        SYSTEM
    }
}
