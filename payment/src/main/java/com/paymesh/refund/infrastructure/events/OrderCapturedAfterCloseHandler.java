package com.paymesh.refund.infrastructure.events;

import com.paymesh.refund.application.CreateRefundCommand;
import com.paymesh.refund.application.CreateRefundService;
import com.paymesh.refund.application.PaymentLookup;
import com.paymesh.refund.application.RefundRepository;
import com.paymesh.refund.application.RefundablePayment;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * Refund's consumer of {@code order.payment_captured_after_close} (ADR-049, Option 1): the provider
 * took money for an order that was already CANCELLED or EXPIRED, so give it back in full.
 *
 * <h2>Idempotent, and every guard is a READ before the create -- never a caught exception</h2>
 * <ol>
 *   <li>The inbox ({@code processed_events}, keyed by {@link #CONSUMER_NAME}) drops an exact
 *       redelivery (same event id).</li>
 *   <li>A duplicate event with a FRESH id (an at-least-once re-emission upstream) is caught by the
 *       STABLE merchant reference {@code auto-close-<paymentIntentId>}: {@link #handle} reads
 *       {@link RefundRepository#existsByMerchantReference} under the payment lock and skips if this
 *       capture's auto-refund was already attempted -- in ANY state, including a FAILED one the
 *       head-room read would not count.</li>
 *   <li>A refund an operator already issued by hand leaves no head-room, caught by the
 *       {@code activeTotalMinor} read (and {@code tr_refunds_within_captured} as the deferred
 *       backstop), so the auto-refund is skipped rather than doubled.</li>
 * </ol>
 *
 * <h2>Why every "nothing to refund" case is a read-then-return, NOT a caught throw</h2>
 *
 * {@code CreateRefundService.create} joins the dispatcher's transaction (the shared
 * {@code TransactionTemplate} is {@code PROPAGATION_REQUIRED}). Any exception it raises --
 * {@code PaymentNotRefundableException}, the over-refund refusal, or the
 * {@code uq_refunds_merchant_reference} collision that {@code save}'s {@code saveAndFlush} surfaces
 * -- marks that shared transaction rollback-only. A {@code try/catch} here cannot undo that: the
 * outer commit still throws {@code UnexpectedRollbackException}, the {@code processed_events} claim
 * rolls back, and the relay redelivers forever (a poison message). So every condition that would
 * make {@code create} throw is checked with a READ first, under the payment's row lock, and
 * {@code create} is called only when it is certain to succeed. Opens no transaction of its own.
 */
public final class OrderCapturedAfterCloseHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCapturedAfterCloseHandler.class);

    /** A STABLE NAME. Renaming it re-opens every closed-order capture ever emitted. */
    private static final String CONSUMER_NAME = "refund.order-captured-after-close";

    private static final String EVENT_TYPE = "order.payment_captured_after_close";

    private static final String REASON = "Payment captured after the order was closed";

    private final PaymentLookup payments;
    private final RefundRepository refunds;
    private final CreateRefundService createRefundService;

    public OrderCapturedAfterCloseHandler(
        PaymentLookup payments,
        RefundRepository refunds,
        CreateRefundService createRefundService
    ) {
        this.payments = payments;
        this.refunds = refunds;
        this.createRefundService = createRefundService;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        Map<String, Object> payload = event.payload();
        // FROM THE ENVELOPE, never the payload.
        MerchantId merchantId = event.merchantId();
        String paymentIntentId = requireText(payload, "paymentIntentId");

        String reference = "auto-close-" + paymentIntentId;

        // THE ROW LOCK FIRST, and every guard below reads under it. CreateRefundService takes the
        // same lock again, re-entrant within this one transaction. Locking first makes the three
        // guards trustworthy: a concurrent duplicate blocks here until the winner commits, then sees
        // the winner's refund and skips.
        Optional<RefundablePayment> found = payments.findRefundableForUpdate(merchantId, paymentIntentId);

        if (found.isEmpty() || !found.get().refundable()) {
            log.warn(
                "Cannot auto-refund a payment captured after its order closed: not refundable "
                    + "paymentIntentId={} merchantId={}", paymentIntentId, merchantId.value()
            );
            return;
        }

        if (refunds.existsByMerchantReference(merchantId, reference)) {
            // This capture's auto-refund was already attempted -- in ANY state, so a FAILED earlier
            // attempt the head-room read would not count is caught here too. Attempting the create
            // would collide on uq_refunds_merchant_reference and poison the transaction, so skip.
            log.info(
                "Captured-after-close auto-refund already attempted for this capture, skipping "
                    + "paymentIntentId={} merchantId={}", paymentIntentId, merchantId.value()
            );
            return;
        }

        if (refunds.activeTotalMinor(paymentIntentId) >= found.get().capturedAmountMinor()) {
            // An operator refunded it by hand (a different reference) -- no head-room left.
            log.info(
                "Captured-after-close payment is already fully refunded, skipping paymentIntentId={} "
                    + "merchantId={}", paymentIntentId, merchantId.value()
            );
            return;
        }

        // null amount = "what is left": the full capture, or the remainder if an operator already
        // refunded part of it by hand. Guarded above, so create() cannot throw here.
        createRefundService.create(new CreateRefundCommand(
            merchantId, paymentIntentId, null, reference, REASON, null
        ));
    }

    private static String requireText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(EVENT_TYPE + " carries no " + key);
        }

        return value.toString();
    }
}
