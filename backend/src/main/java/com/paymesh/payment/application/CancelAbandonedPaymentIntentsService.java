package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentNotCancellableException;
import com.paymesh.payment.domain.PaymentStateChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cancels checkouts nobody came back to.
 * <p>
 * <b>Why this exists at all: an abandoned intent holds its order's only live slot forever, and a
 * held slot is a dead order.</b> {@code uq_payment_intents_live_per_order} (ADR-011) frees the slot
 * only on FAILED or CANCELLED, and a customer who opens a checkout and closes the tab leaves the
 * intent in REQUIRES_PAYMENT_METHOD indefinitely. The merchant then cannot create a second intent,
 * cannot mark the order paid, and cannot recreate the order under their own reference. ADR-015's
 * timeout covers exactly one of the five live states; this covers the two that a customer reaches
 * by doing nothing, which is the common case rather than the exotic one.
 * <p>
 * It also removes a way one merchant could degrade every other. The expiry sweep orders candidates
 * by deadline and skips any order holding a live intent (ADR-014); a permanently held order has the
 * oldest deadline, so it sorts first and reappears in every batch. Enough abandoned checkouts and
 * the batch is entirely held orders and <b>no order on the platform is ever examined again</b>.
 * Releasing abandoned slots removes the permanent hold, so the batch drains.
 * <p>
 * <b>This is NOT the PROCESSING timeout and must never be merged with it.</b> The two look alike and
 * their safety arguments are opposites. An intent in PROCESSING has an attempt in flight that may
 * already have succeeded at the provider, so ADR-015 accepts a real risk of recording a real payment
 * as failed and pays for it with a generous age. The states here precede a provider being told
 * anything at all: no money can be in flight, so cancelling cannot contradict reality, and the age
 * is a product decision about how long to hold a checkout open rather than a money decision. One
 * knob for both would silently apply one of those arguments to the other.
 */
public final class CancelAbandonedPaymentIntentsService {

    private static final Logger log =
        LoggerFactory.getLogger(CancelAbandonedPaymentIntentsService.class);

    /**
     * Written to {@code cancellation_reason}, so a merchant reading the intent can tell this from a
     * cancellation they requested.
     */
    static final String REASON = "Abandoned before confirmation";

    private static final String ACTOR = "SYSTEM";

    private final PaymentIntentRepository paymentIntents;
    private final CancelPaymentIntentService cancelPaymentIntent;
    private final Clock clock;
    private final Duration age;
    private final int batchSize;

    public CancelAbandonedPaymentIntentsService(
        PaymentIntentRepository paymentIntents,
        CancelPaymentIntentService cancelPaymentIntent,
        Clock clock,
        Duration age,
        int batchSize
    ) {
        if (age == null || age.isNegative() || age.isZero()) {
            throw new IllegalArgumentException("Abandoned intent age must be a positive duration");
        }

        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least 1");
        }

        this.paymentIntents = paymentIntents;
        this.cancelPaymentIntent = cancelPaymentIntent;
        this.clock = clock;
        this.age = age;
        this.batchSize = batchSize;
    }

    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(age);

        List<PaymentIntent> candidates =
            paymentIntents.findAbandonedBeforeConfirmation(cutoff, batchSize);

        int cancelled = 0;
        int held = 0;
        int errored = 0;

        for (PaymentIntent candidate : candidates) {
            try {
                // The merchant is read off the candidate row, never supplied -- the same rule the
                // other two sweeps follow. The cancel it delegates to re-reads the intent under a row
                // lock and asks the aggregate again, so a customer who returns between this query and
                // that lock is served rather than cancelled underneath.
                cancelPaymentIntent.cancel(
                    candidate.merchantId(),
                    candidate.paymentIntentId(),
                    REASON,
                    PaymentStateChange.ActorType.SYSTEM,
                    ACTOR
                );

                cancelled++;
            } catch (PaymentIntentNotCancellableException moved) {
                // Not a failure. The intent left a cancellable state after the candidate query read
                // it -- the customer came back and confirmed, or a merchant cancelled it first. The
                // aggregate refusing is the race being resolved correctly.
                held++;
            } catch (RuntimeException failure) {
                // ONE BAD ROW MUST NOT STOP THE SWEEP. Without this the oldest unprocessable intent
                // sits at the head of every batch and nothing behind it is ever reached again --
                // which is the same starvation this class exists to remove.
                errored++;
                log.warn(
                    "Could not cancel abandoned payment intent {}",
                    candidate.paymentIntentId().value(),
                    failure
                );
            }
        }

        return new SweepResult(candidates.size(), cancelled, held, errored);
    }

    /**
     * @param held intents that stopped being cancellable between the query and the lock, which is
     *             the expected outcome of a customer returning mid-sweep rather than an error
     */
    public record SweepResult(int examined, int cancelled, int held, int errored) {
    }
}
