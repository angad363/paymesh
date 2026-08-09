package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

import java.util.Optional;
import java.util.function.BiFunction;

public final class GetPaymentIntentService {

    private final PaymentIntentRepository paymentIntents;

    public GetPaymentIntentService(PaymentIntentRepository paymentIntents) {
        this.paymentIntents = paymentIntents;
    }

    /**
     * The merchantId argument is the authorization, not a filter: an intent belonging to another
     * merchant is reported as not found, so a {@code pi_} in a path never proves the caller may read
     * the row.
     */
    public PaymentIntent getById(MerchantId merchantId, PaymentIntentId paymentIntentId) {
        return require(merchantId, paymentIntentId, paymentIntents::findByPaymentIntentId);
    }

    /**
     * The same read, holding a row lock until the caller's transaction ends.
     * <p>
     * Every transition uses this rather than {@link #getById}: a transition decides from what it
     * read and then writes, and two of those racing on one intent would otherwise collide on the
     * optimistic version and hand the loser a 500. Under the lock the loser waits, sees the winner's
     * state, and gets a refusal that names it.
     * <p>
     * MUST be called inside a transaction.
     */
    /**
     * The velocity question, answered by Payment because these are Payment's rows.
     * <p>
     * Exists so Risk does not have to reach into {@code PaymentIntentJpaEntity} itself.
     * {@code ModuleBoundaryTest} forbids that shortcut for every other capability and this is the
     * capability that tried it -- the first draft of {@code PaymentModuleVelocityLookup} declared
     * its own {@code JpaRepository} over Payment's entity, which is precisely what the boundary
     * test's own javadoc names as the thing it refuses.
     */
    public long countForCustomerSince(
        MerchantId merchantId, String customerId, Instant createdAfter, PaymentIntentId excluding
    ) {
        return paymentIntents.countForCustomerSince(merchantId, customerId, createdAfter, excluding);
    }

    public PaymentIntent getByIdForUpdate(MerchantId merchantId, PaymentIntentId paymentIntentId) {
        return require(merchantId, paymentIntentId, paymentIntents::findByPaymentIntentIdForUpdate);
    }

    private PaymentIntent require(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        BiFunction<MerchantId, PaymentIntentId, Optional<PaymentIntent>> read
    ) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null");
        }

        if (paymentIntentId == null) {
            throw new IllegalArgumentException("Payment Intent ID cannot be null");
        }

        return read.apply(merchantId, paymentIntentId)
            .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));
    }
}
