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
 * number PostgreSQL already computes from rows it already has. Adding a second datastore to avoid one
 * indexed count is the trade running the wrong way.
 * <p>
 * <b>The index did not exist when this was written and V27 adds it.</b> V8 indexed
 * {@code (merchant_id, created_at)} and {@code (merchant_id, order_id)} but nothing on customer, so
 * this count would have been a scan of the merchant's whole history -- taken while the confirm
 * transaction holds the payment intent's row lock, which is the shape of an outage rather than a
 * slow query. Claiming an index exists is not the same as checking.
 * <p>
 * <b>Add Redis when this query appears in slow logs.</b> That is a measurement, and it will arrive
 * long after the row counts here are interesting. Note that dropping Redis also removes SDD
 * §14.6's fail-open requirement rather than skipping it: with no second system, there is nothing to
 * be down, and the count either succeeds inside the confirm transaction or the confirm fails as a
 * unit.
 */
public interface PaymentVelocityLookup {

    /**
     * How many intents this merchant has OPENED for this customer since {@code since}.
     * <p>
     * Created, not confirmed -- there is no status predicate, so an abandoned or cancelled intent
     * counts too. That is a defensible velocity signal (opening ten checkouts in an hour is the
     * pattern, whether or not they were all confirmed) and the name says so. An earlier draft of
     * this javadoc said "confirmed", which the query never did.
     *
     * @param customerId never null -- a guest checkout has no customer to count and the caller must
     *                   not invent one. See {@code RiskFeatures.intentsInWindow}.
     */
    /**
     * @param excludingIntentId the intent being evaluated. EXCLUDED, because it was created inside
     *     this same window and counting the subject of the question makes every threshold fire one
     *     confirm early -- the classic off-by-one in a velocity feature, and invisible unless the
     *     query is exercised against real rows.
     */
    int intentsCreatedSince(
        MerchantId merchantId, String customerId, Instant since, String excludingIntentId
    );
}
