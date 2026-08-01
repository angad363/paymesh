package com.paymesh.payment.application;

import com.paymesh.payment.application.TimeOutProcessingPaymentsService.SweepResult;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PROCESSING timeout's rules, in plain JUnit with no scheduler and no database (ADR-015).
 * <p>
 * The two tests that matter most are {@code failsAnIntentStrandedBeyondTheAge} -- the hole this
 * closes -- and {@code doesNotFireBeforeTheAgeHasElapsed}, which is the guard on the money-adjacent
 * belief. Timing out writes FAILED, which says "we believe this payment did not happen" with no
 * evidence; firing early is how that belief becomes wrong, and how a real payment gets recorded as
 * a failure.
 */
class TimeOutProcessingPaymentsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final Duration AGE = Duration.ofHours(1);
    private static final String ORDER_ID = "ord_11111111-1111-4111-8111-111111111111";
    private static final long AMOUNT = 1999;

    private final InMemoryPaymentIntentRepository repository = new InMemoryPaymentIntentRepository();
    private final Fakes.ImmediateTransactions transactions = new Fakes.ImmediateTransactions();
    private final Fakes.RecordingHistory history = new Fakes.RecordingHistory(transactions);
    private final Fakes.RecordingOutbox outbox = new Fakes.RecordingOutbox(transactions);

    private final TimeOutProcessingPaymentsService service = serviceWithBatchSize(100);

    // --- it fires -------------------------------------------------------------------------

    /**
     * THE HOLE THIS CLOSES. Before it, an intent whose callback never arrived sat in PROCESSING
     * forever -- cancel refused by design, no other exit -- and because it holds its order's only
     * live slot (ADR-011) the order was stuck with it. FAILED is what releases that slot.
     */
    @Test
    void failsAnIntentStrandedBeyondTheAge() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = processingSince(merchantId, NOW.minus(AGE).minusSeconds(60));

        SweepResult result = service.sweep();

        assertEquals(new SweepResult(1, 1, 0, 0), result);

        PaymentIntent failed = repository.findByPaymentIntentId(merchantId, intentId).orElseThrow();

        assertEquals(PaymentIntentStatus.FAILED, failed.status());
        assertEquals(NOW, failed.updatedAt());
    }

    /**
     * THE FAILURE CODE MUST NOT LOOK LIKE A DECLINE. {@code do_not_honour} would claim the issuer
     * refused; the issuer said nothing at all, and everything downstream -- support, reporting, the
     * reconciliation job that does not exist yet -- has to be able to tell those two apart at a
     * glance. The message says out loud that the payment may still have succeeded.
     */
    @Test
    void recordsAFailureCodeThatSaysTheProviderNeverAnswered() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = processingSince(merchantId, NOW.minus(AGE).minusSeconds(60));

        service.sweep();

        PaymentIntent failed = repository.findByPaymentIntentId(merchantId, intentId).orElseThrow();

        assertEquals("provider_no_response", failed.failureCode());
        assertTrue(failed.failureMessage().contains("did not report an outcome"));
        assertTrue(failed.failureMessage().contains("reconciliation"));
    }

    /**
     * FAILED RELEASES THE ORDER'S SLOT, which is the entire operational point. The status set here is
     * the same one {@code uq_payment_intents_live_per_order} excludes, so a merchant whose callback
     * was lost can create a fresh intent and try again.
     */
    @Test
    void releasesTheOrdersLiveIntentSlot() {
        MerchantId merchantId = MerchantId.generate();
        processingSince(merchantId, NOW.minus(AGE).minusSeconds(60));

        assertTrue(repository.existsLiveForOrder(merchantId, ORDER_ID));

        service.sweep();

        assertTrue(
            !repository.existsLiveForOrder(merchantId, ORDER_ID),
            "a timed-out intent must not keep holding its order's only slot"
        );
    }

    /** Exactly on the boundary counts as elapsed: the cutoff comparison is at-or-before. */
    @Test
    void firesForAnIntentExactlyAtTheAge() {
        MerchantId merchantId = MerchantId.generate();
        processingSince(merchantId, NOW.minus(AGE));

        assertEquals(1, service.sweep().failed());
    }

    // --- it does not fire early -------------------------------------------------------------

    /**
     * THE GUARD ON THE BELIEF, AND THE MOST IMPORTANT TEST IN THIS CLASS.
     * <p>
     * An intent one second short of the age is a payment the provider may be about to answer for.
     * Failing it says the collection did not happen when it may well have -- and because FAILED
     * releases the slot, the merchant can then create a second intent and take the money twice.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the age from
     * {@code TimeOutProcessingPaymentsService.sweep} -- pass {@code now} to
     * {@code findStrandedInProcessing} instead of {@code now.minus(age)} -- or drop the
     * {@code intent.updatedAt().isAfter(cutoff)} half of the re-check. Either fires on an intent that
     * has barely started.
     */
    @Test
    void doesNotFireBeforeTheAgeHasElapsed() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = processingSince(merchantId, NOW.minus(AGE).plusSeconds(1));

        SweepResult result = service.sweep();

        assertEquals(new SweepResult(0, 0, 0, 0), result);
        assertEquals(
            PaymentIntentStatus.PROCESSING,
            repository.findByPaymentIntentId(merchantId, intentId).orElseThrow().status()
        );
        assertEquals(0, transactions.executions());
        assertEquals(0, history.changes().size());
        assertEquals(0, outbox.events().size());
    }

    /** An intent confirmed a moment ago is the ordinary case and must be left entirely alone. */
    @Test
    void doesNotFireForAnIntentConfirmedJustNow() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = processingSince(merchantId, NOW);

        assertEquals(0, service.sweep().failed());
        assertEquals(
            PaymentIntentStatus.PROCESSING,
            repository.findByPaymentIntentId(merchantId, intentId).orElseThrow().status()
        );
    }

    /**
     * ONLY PROCESSING TIMES OUT. Every other state either has a merchant-driven exit or is already
     * terminal, so an old REQUIRES_ACTION -- a customer who wandered off mid-challenge -- is not this
     * job's business. Cancelling it is the merchant's call, and that route exists.
     */
    @Test
    void ignoresOldIntentsInEveryOtherState() {
        MerchantId merchantId = MerchantId.generate();
        Instant longAgo = NOW.minus(AGE).minusSeconds(3600);

        repository.save(newIntent(merchantId));
        repository.save(newIntent(merchantId).attach(PaymentMethodType.CARD, longAgo));
        repository.save(newIntent(merchantId)
            .attach(PaymentMethodType.CARD, longAgo).confirm(longAgo).requireAction(longAgo));
        repository.save(newIntent(merchantId)
            .attach(PaymentMethodType.CARD, longAgo).confirm(longAgo).authorize(longAgo));
        repository.save(newIntent(merchantId)
            .attach(PaymentMethodType.CARD, longAgo).confirm(longAgo).succeed(AMOUNT, longAgo));
        repository.save(newIntent(merchantId).cancel("gave up", longAgo));

        assertEquals(new SweepResult(0, 0, 0, 0), service.sweep());
    }

    // --- idempotency ---------------------------------------------------------------------------

    /**
     * RUNNING TWICE WRITES NOTHING TWICE. The re-check under the lock finds FAILED and stops before
     * any write, so no second timeline row and no second {@code payment.failed} -- announcing one
     * failure twice would have a consumer believe two collections went wrong.
     */
    @Test
    void writesNothingASecondTimeWhenTheSweepRunsTwice() {
        MerchantId merchantId = MerchantId.generate();
        processingSince(merchantId, NOW.minus(AGE).minusSeconds(60));

        assertEquals(1, service.sweep().failed());
        assertEquals(0, service.sweep().failed());

        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
    }

    // --- batching and tenancy --------------------------------------------------------------------

    @Test
    void takesAtMostOneBatchPerSweep() {
        MerchantId merchantId = MerchantId.generate();
        processingSince(merchantId, NOW.minus(AGE).minusSeconds(30));
        processingSince(merchantId, NOW.minus(AGE).minusSeconds(60));
        processingSince(merchantId, NOW.minus(AGE).minusSeconds(90));

        TimeOutProcessingPaymentsService batched = serviceWithBatchSize(2);

        assertEquals(2, batched.sweep().failed());
        assertEquals(1, batched.sweep().failed());
        assertEquals(0, batched.sweep().failed());
    }

    /**
     * Tenant-agnostic sweeping, tenant-safe writing: both merchants' intents are found, and each row
     * written carries its own merchant.
     */
    @Test
    void sweepsAcrossMerchantsAndWritesEachRowUnderItsOwnMerchant() {
        MerchantId first = MerchantId.generate();
        MerchantId second = MerchantId.generate();
        PaymentIntentId firstIntent = processingSince(first, NOW.minus(AGE).minusSeconds(60));
        PaymentIntentId secondIntent = processingSince(second, NOW.minus(AGE).minusSeconds(30));

        assertEquals(2, service.sweep().failed());

        assertEquals(
            Map.of(firstIntent.value(), first, secondIntent.value(), second),
            history.changes().stream().collect(java.util.stream.Collectors.toMap(
                change -> change.paymentIntentId().value(), PaymentStateChange::merchantId
            ))
        );
        assertEquals(
            Map.of(firstIntent.value(), first, secondIntent.value(), second),
            outbox.events().stream().collect(java.util.stream.Collectors.toMap(
                OutboxEvent::aggregateId, OutboxEvent::merchantId
            ))
        );
    }

    // --- the timeline and the event ----------------------------------------------------------------

    /**
     * SYSTEM, NOT PROVIDER, AND THE DISTINCTION IS THE POINT. No provider said anything; marking this
     * row PROVIDER would be the platform putting words in its mouth, and it is the first thing anyone
     * auditing a disputed payment will look for.
     */
    @Test
    void writesOneSystemTimelineRowAndOneEventInsideTheTransaction() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = processingSince(merchantId, NOW.minus(AGE).minusSeconds(60));

        service.sweep();

        assertEquals(1, transactions.executions());
        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
        assertTrue(history.appendedInsideATransaction());
        assertTrue(outbox.appendedInsideATransaction());

        PaymentStateChange change = history.changes().get(0);

        assertEquals(intentId, change.paymentIntentId());
        assertEquals(PaymentIntentStatus.PROCESSING, change.fromStatus());
        assertEquals(PaymentIntentStatus.FAILED, change.toStatus());
        assertEquals(PaymentStateChange.ActorType.SYSTEM, change.actorType());
        assertNull(change.actorId());
        assertEquals("provider_no_response", change.reason());
        assertEquals(NOW, change.occurredAt());
    }

    @Test
    void announcesPaymentFailedCarryingTheTimeoutCode() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = processingSince(merchantId, NOW.minus(AGE).minusSeconds(60));

        service.sweep();

        OutboxEvent event = outbox.events().get(0);

        assertEquals("payment.failed", event.eventType());
        assertEquals("PAYMENT_INTENT", event.aggregateType());
        assertEquals(intentId.value(), event.aggregateId());
        assertEquals(merchantId, event.merchantId());
        assertEquals(1, event.eventVersion());

        Map<String, Object> payload = event.payload();

        assertEquals(ORDER_ID, payload.get("orderId"));
        assertEquals("PROCESSING", payload.get("previousStatus"));
        assertEquals("FAILED", payload.get("status"));
        // The one field that tells a consumer this was a timeout rather than a decline. Dropping it
        // would make an unresolved payment indistinguishable from a refused one.
        assertEquals("provider_no_response", payload.get("failureCode"));
        assertEquals(0L, payload.get("capturedAmountMinor"));
        assertEquals(NOW.toString(), payload.get("failedAt"));
    }

    // --- construction ------------------------------------------------------------------------------

    /** A zero or negative age would time out every in-flight payment on the first sweep. */
    @Test
    void refusesANonPositiveAge() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithAge(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> serviceWithAge(Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class, () -> serviceWithAge(null));
    }

    @Test
    void refusesABatchSizeBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithBatchSize(0));
    }

    @Test
    void doesNothingWhenThereIsNothingToSweep() {
        assertEquals(new SweepResult(0, 0, 0, 0), service.sweep());
        assertEquals(0, transactions.executions());
    }

    // --- helpers --------------------------------------------------------------------------------------

    private TimeOutProcessingPaymentsService serviceWithBatchSize(int batchSize) {
        return new TimeOutProcessingPaymentsService(
            repository, history, outbox, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC), AGE, batchSize
        );
    }

    private TimeOutProcessingPaymentsService serviceWithAge(Duration age) {
        return new TimeOutProcessingPaymentsService(
            repository, history, outbox, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC), age, 100
        );
    }

    /**
     * An intent sitting in PROCESSING since {@code confirmedAt}. The confirm stamps {@code updatedAt}
     * and that is exactly what the age is measured from -- the same column the real query reads.
     */
    private PaymentIntentId processingSince(MerchantId merchantId, Instant confirmedAt) {
        return repository.save(
            newIntent(merchantId)
                .attach(PaymentMethodType.CARD, confirmedAt)
                .confirm(confirmedAt)
        ).paymentIntentId();
    }

    private static PaymentIntent newIntent(MerchantId merchantId) {
        return PaymentIntent.create(
            PaymentIntentId.generate(),
            merchantId,
            ORDER_ID,
            null,
            AMOUNT,
            "INR",
            null,
            null,
            Map.of(),
            NOW.minus(Duration.ofDays(1))
        );
    }
}
