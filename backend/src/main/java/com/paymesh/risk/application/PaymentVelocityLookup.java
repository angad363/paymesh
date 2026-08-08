package com.paymesh.risk.application;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * What Risk needs to know about Payment, defined by Risk (ADR-008, the same shape as Payment's own
 * {@code OrderLookup}).
 *
 * <h2>WHY THIS IS A COUNT AND NOT REDIS</h2>
 *
 * The plan called for velocity counters in Redis. That is a container, a Testcontainers
 * dependency, a fail-open policy and an entire outage mode on the money path, in exchange for a
 * number PostgreSQL already computes from rows it already has -- {@code payment_intents} is indexed
 * on the columns this counts. Adding a second datastore to avoid one indexed count is the trade
 * running the wrong way.
 * <p>
 * <b>Add Redis when this query appears in slow logs.</b> That is a measurement, and it will arrive
 * long after the row counts here are interesting. Note that dropping Redis also removes SDD
 * §14.6's fail-open requirement rather than skipping it: with no second system, there is nothing to
 * be down, and the count either succeeds inside the confirm transaction or the confirm fails as a
 * unit.
 */
public interface PaymentVelocityLookup {

    /**
     * How many intents this merchant has confirmed for this customer since {@code since}.
     *
     * @param customerId never null -- a guest checkout has no customer to count and the caller must
     *                   not invent one. See {@code RiskFeatures.confirmsInWindow}.
     */
    int confirmsSince(MerchantId merchantId, String customerId, Instant since);
}
