package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;

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
