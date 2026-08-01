package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

public final class CancelPaymentIntentService {

    private final PaymentIntentRepository paymentIntents;
    private final PaymentStateHistoryRepository history;
    private final GetPaymentIntentService getPaymentIntentService;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CancelPaymentIntentService(
        PaymentIntentRepository paymentIntents,
        PaymentStateHistoryRepository history,
        GetPaymentIntentService getPaymentIntentService,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.paymentIntents = paymentIntents;
        this.history = history;
        this.getPaymentIntentService = getPaymentIntentService;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Requests cancellation. The service does not decide whether it is allowed -- it loads the
     * aggregate and asks, so the state machine has exactly one implementation and a second caller
     * cannot reach a different conclusion.
     * <p>
     * Cancelling is also what releases the order's live-intent slot (ADR-011), so this is the path
     * a merchant takes to abandon a collection and start another.
     */
    public PaymentIntent cancel(MerchantId merchantId, PaymentIntentId paymentIntentId, String reason) {
        PaymentIntent intent = getPaymentIntentService.getById(merchantId, paymentIntentId);

        Instant now = Instant.now(clock);
        PaymentIntentStatus from = intent.status();
        PaymentIntent cancelled = intent.cancel(reason, now);

        // The transition and its history row commit together or not at all, for the same reason
        // creation's three writes do: a timeline missing a transition that happened is worse than
        // no timeline, because it looks complete.
        return transactions.execute(status -> {
            PaymentIntent saved = paymentIntents.save(cancelled);

            history.append(new PaymentStateChange(
                saved.merchantId(),
                saved.paymentIntentId(),
                from,
                saved.status(),
                PaymentStateChange.ActorType.MERCHANT,
                saved.merchantId().value(),
                saved.cancellationReason(),
                now
            ));

            return saved;
        });
    }
}
