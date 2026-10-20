package com.paymesh.order.application;

import com.paymesh.shared.tenant.MerchantId;

/**
 * Whether an order is still being collected against, defined by Order and answered by whoever can
 * (ADR-008, ADR-014).
 *
 * <h2>Read this before "simplifying" the wiring</h2>
 *
 * The expiry sweeper must not expire an order that has a live payment intent: doing so leaves that
 * intent holding the slot of an order that can no longer be paid, with no route back through the
 * API. That is open item 1's shape and reintroducing it is the specific thing ADR-014 exists to
 * prevent.
 * <p>
 * But <b>Order must never learn that Payment exists</b> (design spec 0.5), and
 * {@code ModuleBoundaryTest.orderNeverImportsPayment} has no exceptions at all. So the question is
 * declared here, in Order's own vocabulary, as a port -- and the implementation lives in
 * {@code com.paymesh.payment.infrastructure.order}, on the side that is already allowed to import
 * the other. Order depends on this interface, which is Order's own type; Payment depends on Order,
 * which it already did. <b>The dependency graph stays acyclic and no import moves.</b>
 * <p>
 * The bean is required. If Payment is ever extracted and nothing implements this, the context fails
 * to start -- which is the correct outcome: a sweeper that cannot ask the question must not run,
 * and a silently-absent implementation would expire live orders in production while every test in
 * the Order module stayed green.
 *
 * <h2>What it is not</h2>
 *
 * It is a CHECK, not a lock, and it cannot be otherwise across a module boundary. The sweeper closes
 * the race by holding the order's own row lock while it asks -- the same row Payment's create path
 * locks -- so the two serialize on the order rather than on this answer. See ADR-014 section 3.
 */
public interface PaymentActivityLookup {

    /**
     * Whether the order currently holds a payment intent that occupies its slot.
     * <p>
     * "Live" is exactly the complement of {@code uq_payment_intents_live_per_order}'s exclusion set:
     * every status but FAILED and CANCELLED. The definition is deliberately Payment's rather than
     * Order's -- Order does not know the vocabulary and must not acquire it -- so an intent that
     * blocks a second create is precisely an intent that blocks an expiry, and the two rules cannot
     * drift into disagreeing about the same order.
     */
    boolean hasLivePaymentIntent(MerchantId merchantId, String orderId);
}
