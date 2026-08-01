package com.paymesh.payment.infrastructure.persistence.jpa;

import com.paymesh.payment.application.OrderHasActivePaymentIntentException;
import com.paymesh.payment.application.PaymentIntentCursor;
import com.paymesh.payment.application.PaymentIntentRepository;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL-backed implementation of the application's PaymentIntentRepository port.
 * Everything JPA stays on this side of the interface; the services see only domain types.
 */
public final class JpaPaymentIntentRepository implements PaymentIntentRepository {

    /**
     * The statuses that release an order's live-intent slot, as one list.
     * <p>
     * It must stay identical to the WHERE clause of {@code uq_payment_intents_live_per_order}. If
     * the two ever disagree, the pre-check answers a different question from the constraint and the
     * friendly error stops matching the actual outcome.
     */
    private static final Set<String> RELEASED_STATUSES = Set.of(
        PaymentIntentStatus.FAILED.name(),
        PaymentIntentStatus.CANCELLED.name()
    );

    private static final String LIVE_PER_ORDER_INDEX = "uq_payment_intents_live_per_order";

    private final SpringDataPaymentIntentRepository paymentIntents;

    public JpaPaymentIntentRepository(SpringDataPaymentIntentRepository paymentIntents) {
        this.paymentIntents = paymentIntents;
    }

    @Override
    public boolean existsLiveForOrder(MerchantId merchantId, String orderId) {
        return paymentIntents.existsByMerchantIdAndOrderIdAndStatusNotIn(
            merchantId.value(), orderId, RELEASED_STATUSES
        );
    }

    @Override
    public PaymentIntent save(PaymentIntent paymentIntent) {
        try {
            PaymentIntentJpaEntity saved =
                paymentIntents.saveAndFlush(PaymentIntentJpaMapper.toEntity(paymentIntent));

            return PaymentIntentJpaMapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            // The service's existsLiveForOrder is a check, not a lock: two concurrent creates can
            // both pass it. The partial unique index is the real guard, and the loser of that race
            // must still get a 409 rather than a 500.
            //
            // Narrowed by CONSTRAINT NAME rather than by guessing from the aggregate's shape. The
            // other integrity failures reachable here are the composite foreign keys, and an intent
            // naming another tenant's order must not be reported to the caller as "you already have
            // one" -- that would be both wrong and a hint about another merchant's data.
            if (ConstraintViolations.violates(exception, LIVE_PER_ORDER_INDEX)) {
                throw new OrderHasActivePaymentIntentException(paymentIntent.orderId());
            }

            throw exception;
        }
    }

    @Override
    public Optional<PaymentIntent> findByPaymentIntentId(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId
    ) {
        return paymentIntents
            .findByMerchantIdAndPaymentIntentId(merchantId.value(), paymentIntentId.value())
            .map(PaymentIntentJpaMapper::toDomain);
    }

    @Override
    public Optional<PaymentIntent> findByPaymentIntentIdForUpdate(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId
    ) {
        return paymentIntents
            .findForUpdateByMerchantIdAndPaymentIntentId(merchantId.value(), paymentIntentId.value())
            .map(PaymentIntentJpaMapper::toDomain);
    }

    @Override
    public Optional<PaymentIntent> findForProviderCallbackForUpdate(PaymentIntentId paymentIntentId) {
        return paymentIntents
            .findForProviderCallbackForUpdate(paymentIntentId.value())
            .map(PaymentIntentJpaMapper::toDomain);
    }

    @Override
    public List<PaymentIntent> findPage(
        MerchantId merchantId,
        PaymentIntentStatus status,
        String orderId,
        PaymentIntentCursor cursor,
        int limit
    ) {
        List<PaymentIntentJpaEntity> page = selectPage(merchantId, status, orderId, cursor, limit);

        return page.stream().map(PaymentIntentJpaMapper::toDomain).toList();
    }

    @Override
    public List<PaymentIntent> findStrandedInProcessing(Instant confirmedBefore, int limit) {
        return paymentIntents.findStrandedInProcessing(confirmedBefore, Limit.of(limit)).stream()
            .map(PaymentIntentJpaMapper::toDomain)
            .toList();
    }

    private List<PaymentIntentJpaEntity> selectPage(
        MerchantId merchantId,
        PaymentIntentStatus status,
        String orderId,
        PaymentIntentCursor cursor,
        int limit
    ) {
        String merchant = merchantId.value();
        Instant cursorCreatedAt = cursor.createdAt();
        String cursorId = cursor.paymentIntentId();
        Limit applied = Limit.of(limit);

        if (status != null && orderId != null) {
            return paymentIntents.findPageByStatusAndOrder(
                merchant, status.name(), orderId, cursorCreatedAt, cursorId, applied
            );
        }

        if (status != null) {
            return paymentIntents.findPageByStatus(
                merchant, status.name(), cursorCreatedAt, cursorId, applied
            );
        }

        if (orderId != null) {
            return paymentIntents.findPageByOrder(
                merchant, orderId, cursorCreatedAt, cursorId, applied
            );
        }

        return paymentIntents.findPage(merchant, cursorCreatedAt, cursorId, applied);
    }
}
