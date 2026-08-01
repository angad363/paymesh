package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Test double for the persistence port. It enforces the same tenant scoping the real adapter gets
 * from the "where merchant_id = ?" predicate, the same keyset ordering the real query gets, and the
 * same released-status set as {@code uq_payment_intents_live_per_order}, so a service test that
 * leaks across tenants or pages ambiguously fails here too rather than only in the container-backed
 * test.
 * <p>
 * What it cannot do is arbitrate a race, which is why the live-per-order rule is proved against
 * PostgreSQL rather than here.
 */
final class InMemoryPaymentIntentRepository implements PaymentIntentRepository {

    private static final Set<PaymentIntentStatus> RELEASED =
        Set.of(PaymentIntentStatus.FAILED, PaymentIntentStatus.CANCELLED);

    private final List<PaymentIntent> intents = new ArrayList<>();

    @Override
    public boolean existsLiveForOrder(MerchantId merchantId, String orderId) {
        return intents.stream().anyMatch(intent -> intent.merchantId().equals(merchantId)
            && intent.orderId().equals(orderId)
            && !RELEASED.contains(intent.status()));
    }

    @Override
    public PaymentIntent save(PaymentIntent paymentIntent) {
        intents.removeIf(stored -> stored.paymentIntentId().equals(paymentIntent.paymentIntentId()));
        intents.add(paymentIntent);
        return paymentIntent;
    }

    @Override
    public Optional<PaymentIntent> findByPaymentIntentId(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId
    ) {
        return intents.stream()
            .filter(intent -> intent.merchantId().equals(merchantId)
                && intent.paymentIntentId().equals(paymentIntentId))
            .findFirst();
    }

    @Override
    public List<PaymentIntent> findPage(
        MerchantId merchantId,
        PaymentIntentStatus status,
        String orderId,
        PaymentIntentCursor cursor,
        int limit
    ) {
        return intents.stream()
            .filter(intent -> intent.merchantId().equals(merchantId))
            .filter(intent -> status == null || intent.status() == status)
            .filter(intent -> orderId == null || orderId.equals(intent.orderId()))
            .filter(intent -> cursor.isAfter(intent.createdAt(), intent.paymentIntentId().value()))
            .sorted(Comparator
                .comparing(PaymentIntent::createdAt)
                .thenComparing((PaymentIntent intent) -> intent.paymentIntentId().value())
                .reversed())
            .limit(limit)
            .toList();
    }
}
