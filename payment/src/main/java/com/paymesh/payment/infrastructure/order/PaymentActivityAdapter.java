package com.paymesh.payment.infrastructure.order;

import com.paymesh.order.application.PaymentActivityLookup;
import com.paymesh.payment.application.PaymentIntentRepository;
import com.paymesh.shared.tenant.MerchantId;

/**
 * Answers Order's {@code PaymentActivityLookup} from Payment's own table (ADR-014).
 *
 * <h2>Why the implementation is on this side of the boundary</h2>
 *
 * Order's expiry sweeper must not expire an order that has a live payment intent, but <b>Order must
 * never learn that Payment exists</b> (design spec 0.5) and
 * {@code ModuleBoundaryTest.orderNeverImportsPayment} has an empty allowlist. So Order declares the
 * question as a port in its own vocabulary and this class answers it, in
 * {@code com.paymesh.payment.infrastructure} -- the direction that was already allowed. Order
 * depends on an interface it owns; Payment depends on Order, which it already did. <b>The dependency
 * graph stays acyclic and no import moves.</b>
 * <p>
 * It sits beside {@link OrderModuleLookup}, which is the traffic going the other way: that class
 * lets Payment read an order, this one lets Order be told about intents without knowing what one is.
 * Both are adapters, both live in infrastructure, and neither leaks a type across the boundary --
 * this method's parameters and return value are a {@code MerchantId}, a {@code String} and a
 * {@code boolean}.
 *
 * <h2>Note it does not import com.paymesh.order.domain</h2>
 *
 * Only {@code com.paymesh.order.application.PaymentActivityLookup}, which is an interface with no
 * Order types on it. {@code ModuleBoundaryTest} allowlists this file for the same reason it
 * allowlists {@code OrderModuleLookup}: an adapter cannot avoid naming the thing it adapts.
 */
public final class PaymentActivityAdapter implements PaymentActivityLookup {

    private final PaymentIntentRepository paymentIntents;

    public PaymentActivityAdapter(PaymentIntentRepository paymentIntents) {
        this.paymentIntents = paymentIntents;
    }

    /**
     * Delegates to the SAME method the create path's slot pre-check uses, and that reuse is the
     * point rather than a convenience.
     * <p>
     * {@code existsLiveForOrder} is defined by the status set that
     * {@code uq_payment_intents_live_per_order} excludes -- FAILED and CANCELLED -- held in one place
     * in {@code JpaPaymentIntentRepository}. So "an intent that blocks a second create" and "an
     * intent that blocks an expiry" are the same predicate by construction, and cannot drift into
     * disagreeing about one order. A second query written specially for the sweeper would have been
     * a second chance to get that set wrong.
     * <p>
     * <b>It is a check, not a lock</b>, and it cannot be otherwise across a module boundary. The
     * sweeper closes the race by holding the ORDER's row lock while it asks -- the same row Payment's
     * create path locks before inserting an intent -- so the two serialize on the order rather than
     * on this answer. ADR-014 section 3.
     */
    @Override
    public boolean hasLivePaymentIntent(MerchantId merchantId, String orderId) {
        return paymentIntents.existsLiveForOrder(merchantId, orderId);
    }
}
