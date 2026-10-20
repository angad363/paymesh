package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.refund.domain.RefundStateChange;
import com.paymesh.refund.domain.RefundStatus;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates a refund and hands it to the provider.
 *
 * <h2>THE PRE-CHECK IS THE ERROR MESSAGE; THE TRIGGER IS THE GUARD</h2>
 *
 * {@link #requireHeadroom} reads the captured amount and the refunds already spoken for, and
 * refuses with both figures named. It cannot be correct on its own: two concurrent partial refunds
 * each read a total that excludes the other, both pass, and both insert.
 * {@code tr_refunds_within_captured} is what refuses the second at COMMIT. The pre-check exists so
 * the ordinary case gets a sentence instead of a constraint violation -- the same division of
 * labour V15 uses for debits-equal-credits, and the one README states for the whole codebase.
 *
 * <h2>Why the refund is written PENDING and then submitted</h2>
 *
 * Both happen in one transaction, so the merchant never observes PENDING through the API. The two
 * steps still exist separately because the ROW has to be in the database, and therefore counted by
 * the over-refund trigger, before anything is told to move money. Creating it already PROCESSING
 * would collapse a real ordering into an implementation detail, and it would leave
 * {@code Refund.cancel} with no state to act on.
 */
public final class CreateRefundService {

    private static final int REFUND_CREATED_VERSION = 1;

    private final RefundRepository refunds;
    private final RefundStateHistoryRepository history;
    private final PaymentLookup payments;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreateRefundService(
        RefundRepository refunds,
        RefundStateHistoryRepository history,
        PaymentLookup payments,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.refunds = refunds;
        this.history = history;
        this.payments = payments;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Refund create(CreateRefundCommand command) {
        Instant now = Instant.now(clock);

        return transactions.execute(status -> {
            // THE LOCK, AND EVERYTHING BELOW DEPENDS ON IT BEING FIRST.
            //
            // Locking the PAYMENT serializes every refund of that payment, which is the only thing
            // that makes the head-room read below trustworthy. Two concurrent refunds otherwise
            // each read a total that excludes the other, both pass, and both commit -- and the
            // deferred trigger does NOT catch it, because a constraint trigger's query runs on the
            // snapshot of the statement that queued it rather than a fresh one. That was measured,
            // not assumed: RefundConcurrencyTest let two full refunds through before this line
            // existed.
            //
            // The lock is on the payment rather than on the refunds, because the rule is about a
            // set of rows that does not exist yet -- there is nothing to lock until the row is
            // written, and by then it is too late.
            RefundablePayment payment = payments
                .findRefundableForUpdate(command.merchantId(), command.paymentIntentId())
                .filter(RefundablePayment::refundable)
                .orElseThrow(() -> new PaymentNotRefundableException(command.paymentIntentId()));

            // FULL REFUND WHEN NO AMOUNT IS GIVEN, and "full" means what is LEFT rather than what
            // was captured. Against a payment already half refunded, omitting the amount refunds
            // the other half; reading it as the captured total would fail the over-refund check
            // every time and make the convenience useless exactly when it is most wanted.
            long alreadySpokenFor = refunds.activeTotalMinor(payment.paymentIntentId());
            long amountMinor = command.amountMinor() == null
                ? payment.capturedAmountMinor() - alreadySpokenFor
                : command.amountMinor();

            requireHeadroom(payment, amountMinor, alreadySpokenFor);

            Refund requested = refunds.save(Refund.request(
                RefundId.generate(),
                command.merchantId(),
                payment.paymentIntentId(),
                amountMinor,
                // THE PAYMENT'S CURRENCY, never the caller's. The request record has no currency
                // field at all, so there is nothing to disagree with -- a refund is denominated in
                // whatever was collected, and tr_refunds_currency_matches enforces it besides.
                payment.currency(),
                command.merchantReference(),
                command.reason(),
                now
            ));

            history.append(new RefundStateChange(
                requested.merchantId(), requested.refundId(), null, RefundStatus.PENDING,
                RefundStateChange.ActorType.MERCHANT, command.actorId(), command.reason(), now
            ));

            Refund submitted = refunds.save(requested.submit(now));

            history.append(new RefundStateChange(
                submitted.merchantId(), submitted.refundId(),
                RefundStatus.PENDING, RefundStatus.PROCESSING,
                RefundStateChange.ActorType.SYSTEM, null, "Handed to the provider", now
            ));

            outbox.append(refundCreated(submitted));

            return submitted;
        });
    }

    /**
     * The readable half of the over-refund rule.
     *
     * @throws RefundExceedsCapturedAmountException naming the requested amount, what is already
     *     spoken for and what was captured -- all three, because "too much" without the figures
     *     leaves a merchant guessing at what would fit
     */
    private static void requireHeadroom(
        RefundablePayment payment,
        long amountMinor,
        long alreadySpokenForMinor
    ) {
        // Reachable by asking for a full refund of a fully-refunded payment: the subtraction above
        // yields zero, and Refund's constructor would throw a bare IllegalArgumentException about
        // positive amounts, which says nothing about refunds. Answered here instead.
        if (amountMinor <= 0) {
            throw new RefundExceedsCapturedAmountException(
                payment.paymentIntentId(), Math.max(amountMinor, 0), alreadySpokenForMinor,
                payment.capturedAmountMinor()
            );
        }

        if (amountMinor + alreadySpokenForMinor > payment.capturedAmountMinor()) {
            throw new RefundExceedsCapturedAmountException(
                payment.paymentIntentId(), amountMinor, alreadySpokenForMinor,
                payment.capturedAmountMinor()
            );
        }
    }

    /**
     * {@code refund.created}. SDD 16.5 lists the Ledger as a consumer for reservation purposes;
     * nothing consumes it yet, and it is emitted anyway because the outbox write is what makes the
     * fact recoverable -- a consumer added later reads the backlog rather than starting blind.
     */
    private static OutboxEvent refundCreated(Refund refund) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("refundId", refund.refundId().value());
        payload.put("merchantId", refund.merchantId().value());
        payload.put("paymentIntentId", refund.paymentIntentId());
        payload.put("amountMinor", refund.amountMinor());
        payload.put("currency", refund.currency());
        payload.put("merchantReference", refund.merchantReference());
        payload.put("reason", refund.reason());
        payload.put("status", refund.status().name());
        payload.put("occurredAt", refund.updatedAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            refund.merchantId(),
            "REFUND",
            refund.refundId().value(),
            "refund.created",
            REFUND_CREATED_VERSION,
            payload,
            refund.updatedAt()
        );
    }
}
