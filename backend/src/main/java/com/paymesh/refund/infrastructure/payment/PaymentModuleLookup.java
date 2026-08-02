package com.paymesh.refund.infrastructure.payment;

import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.PaymentIntentNotFoundException;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.refund.application.PaymentLookup;
import com.paymesh.refund.application.RefundablePayment;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

/**
 * THE ONLY FILE IN {@code com.paymesh.refund} PERMITTED TO NAME PAYMENT.
 * <p>
 * {@code ModuleBoundaryTest.refundImportsPaymentOnlyThroughItsAdapter} allowlists exactly this
 * directory. Everything else in Refund -- the aggregate, the services, the controllers, the
 * consumers -- is written as if Payment were a different process, and the day it is, this class is
 * the one that becomes an HTTP client.
 *
 * <h2>IT ANSWERS A QUESTION RATHER THAN RETURNING A STATUS</h2>
 *
 * {@link RefundablePayment#refundable()} is a boolean computed HERE, from
 * {@link PaymentIntentStatus}. The alternative -- returning the status and letting Refund decide --
 * would put Payment's enum in Refund's vocabulary, and Refund would then have to be updated every
 * time Payment gained a state. This way, "what counts as refundable" is one line in the module that
 * owns the state machine.
 *
 * <h2>Not found and not yours are the same answer</h2>
 *
 * {@code getById} throws {@code PaymentIntentNotFoundException} for both, because Payment already
 * refuses to distinguish them (ADR-007). Caught and turned into an empty Optional, which the
 * service turns into one {@code PAYMENT_NOT_REFUNDABLE} whatever the cause.
 */
public final class PaymentModuleLookup implements PaymentLookup {

    private final GetPaymentIntentService paymentIntents;

    public PaymentModuleLookup(GetPaymentIntentService paymentIntents) {
        this.paymentIntents = paymentIntents;
    }

    @Override
    public Optional<RefundablePayment> findRefundable(MerchantId merchantId, String paymentIntentId) {
        PaymentIntent intent;

        try {
            intent = paymentIntents.getById(merchantId, PaymentIntentId.from(paymentIntentId));
        } catch (PaymentIntentNotFoundException | IllegalArgumentException exception) {
            // IllegalArgumentException too: a malformed "pi_" id is a payment that does not exist,
            // and answering 400 here would tell a caller that a well-formed id they guessed was at
            // least the right SHAPE, which is the first step of enumerating them.
            return Optional.empty();
        }

        return Optional.of(new RefundablePayment(
            intent.paymentIntentId().value(),
            // CAPTURED, never the authorized or requested amount. On a partial capture the others
            // are larger, and refunding against them would send out money that never came in.
            intent.capturedAmountMinor(),
            intent.currency(),
            isRefundable(intent)
        ));
    }

    /**
     * A payment is refundable when it has collected money and has not been fully given back.
     * <p>
     * {@code PARTIALLY_REFUNDED} is included and {@code REFUNDED} is not: the first still has
     * head-room, the second has none by definition. The precise arithmetic is not done here --
     * {@code CreateRefundService} compares the amounts and the deferred trigger enforces them.
     * This is only the coarse question of whether the payment is in a state where refunding is
     * meaningful at all.
     */
    private static boolean isRefundable(PaymentIntent intent) {
        return intent.capturedAmountMinor() > 0
            && (intent.status() == PaymentIntentStatus.SUCCEEDED
                || intent.status() == PaymentIntentStatus.PARTIALLY_REFUNDED);
    }
}
