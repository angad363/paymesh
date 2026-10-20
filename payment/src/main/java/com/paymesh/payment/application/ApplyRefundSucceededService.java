package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentNotRefundableException;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Records against a payment that one of its refunds succeeded.
 *
 * <h2>PAYMENT MOVES ITS OWN COLUMN</h2>
 *
 * Refund cannot write {@code payment_intents}, any more than Payment writes {@code orders}. So
 * Refund announces and Payment applies -- the ADR-016 shape, now used a second time. This is what
 * makes {@code PARTIALLY_REFUNDED} and {@code REFUNDED} reachable for the first time since V8
 * declared them.
 *
 * <h2>A payment that cannot record the refund is logged, not retried</h2>
 *
 * The handler must be idempotent and must throw to retry -- but throwing here would retry forever
 * against a fact that will not change: a refund succeeded against a payment Payment believes
 * collected nothing, or has already returned everything. That is a reconciliation problem between
 * two modules, and the honest response is a WARN and a consumed event rather than an event that
 * jams its aggregate's queue permanently (ADR-016 names that as the largest hole in delivery).
 * <p>
 * Redelivery of the SAME event is not this case: {@code processed_events} stops it before the
 * handler runs.
 */
public final class ApplyRefundSucceededService {

    private static final Logger log = LoggerFactory.getLogger(ApplyRefundSucceededService.class);

    private static final String REASON = "A refund of this payment succeeded";

    private final PaymentIntentRepository paymentIntents;
    private final PaymentStateHistoryRepository history;
    private final GetPaymentIntentService getPaymentIntentService;

    public ApplyRefundSucceededService(
        PaymentIntentRepository paymentIntents,
        PaymentStateHistoryRepository history,
        GetPaymentIntentService getPaymentIntentService
    ) {
        this.paymentIntents = paymentIntents;
        this.history = history;
        this.getPaymentIntentService = getPaymentIntentService;
    }

    /** @return true when the payment moved */
    public boolean apply(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        long refundAmountMinor,
        Instant occurredAt
    ) {
        // Row-locked: two partial refunds of one payment can succeed at the same moment, and both
        // consumers would otherwise read the same refunded total and each add their own amount to
        // it, losing one of them.
        PaymentIntent intent = getPaymentIntentService.getByIdForUpdate(merchantId, paymentIntentId);

        PaymentIntentStatus from = intent.status();

        PaymentIntent refunded;

        try {
            refunded = intent.applyRefund(refundAmountMinor, occurredAt);
        } catch (PaymentIntentNotRefundableException | IllegalArgumentException exception) {
            log.warn(
                "A refund succeeded against a payment that cannot record it, leaving the payment"
                    + " untouched paymentIntentId={} merchantId={} status={} amountMinor={}: {}",
                paymentIntentId.value(), merchantId.value(), from, refundAmountMinor,
                exception.getMessage()
            );

            return false;
        }

        PaymentIntent saved = paymentIntents.save(refunded);

        history.append(new PaymentStateChange(
            saved.merchantId(),
            saved.paymentIntentId(),
            from,
            saved.status(),
            PaymentStateChange.ActorType.SYSTEM,
            null,
            REASON,
            occurredAt
        ));

        return true;
    }
}
