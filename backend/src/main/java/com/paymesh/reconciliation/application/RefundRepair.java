package com.paymesh.reconciliation.application;

import java.time.Instant;

/**
 * This module's way into Refund, and the mirror of {@link PaymentRepair} for money going back out.
 * <p>
 * Same argument, sharper stakes. ADR-019's known gap was a refund whose callback never arrived
 * sitting in PROCESSING and holding its amount against the captured total; ADR-023 added a sweeper
 * that eventually fails it, which the sweeper's own javadoc calls a guess in the safe direction and
 * names reconciliation as the real answer. This is that answer: a refund the provider actually
 * completed is moved to SUCCEEDED from the provider's record rather than being left FAILED on a
 * timer's assumption.
 *
 * @see RepairOutcome
 */
public interface RefundRepair {

    /**
     * @param succeeded the provider's verdict. Only two states exist on this path -- a provider
     *                  either sent the money back or refused to -- which is why this is a boolean
     *                  rather than a third enum nobody would have a fourth value for.
     */
    RepairOutcome replay(
        String refundId,
        String providerReference,
        boolean succeeded,
        String failureCode,
        String failureMessage,
        String eventId,
        Instant occurredAt
    );
}
