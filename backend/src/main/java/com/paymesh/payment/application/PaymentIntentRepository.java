package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

/**
 * The payment capability's persistence port.
 * <p>
 * Every read takes a MerchantId. That is the tenant boundary expressed as a method signature: there
 * is deliberately no findByPaymentIntentId(PaymentIntentId), because a caller holding only an id --
 * guessed, leaked, or copied from another merchant's response -- must not be able to reach a row.
 */
public interface PaymentIntentRepository {

    /**
     * A convenience for a friendlier error, NOT the uniqueness guarantee. Two concurrent creates can
     * both pass this and {@code uq_payment_intents_live_per_order} is what stops both from landing,
     * so an implementation must still translate the violation rather than rely on callers checking
     * first.
     */
    boolean existsLiveForOrder(MerchantId merchantId, String orderId);

    PaymentIntent save(PaymentIntent paymentIntent);

    Optional<PaymentIntent> findByPaymentIntentId(MerchantId merchantId, PaymentIntentId paymentIntentId);

    /**
     * One page of the merchant's intents, newest first, starting strictly after {@code cursor} and
     * ordered by {@code (createdAt, paymentIntentId)} descending. The tiebreak is part of the
     * contract: an implementation that orders by timestamp alone will skip or repeat rows that
     * share one.
     *
     * @param status  filters by status when given; null means every status
     * @param orderId filters to one order's intents when given; null means every order
     */
    List<PaymentIntent> findPage(
        MerchantId merchantId,
        PaymentIntentStatus status,
        String orderId,
        PaymentIntentCursor cursor,
        int limit
    );
}
