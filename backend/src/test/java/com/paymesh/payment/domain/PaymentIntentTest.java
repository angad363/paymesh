package com.paymesh.payment.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentIntentTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");
    private static final String ORDER_ID = "ord_11111111-1111-4111-8111-111111111111";

    @Test
    void createsAnIntentAwaitingAPaymentMethod() {
        MerchantId merchantId = MerchantId.generate();

        PaymentIntent intent = intent(merchantId, null, 1999, "INR", CaptureMethod.AUTOMATIC);

        assertEquals(merchantId, intent.merchantId());
        assertEquals(ORDER_ID, intent.orderId());
        assertEquals(1999L, intent.amountMinor());
        assertEquals("INR", intent.currency());
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, intent.status());
        assertEquals(NOW, intent.createdAt());
        assertEquals(NOW, intent.updatedAt());
        assertNull(intent.version());
    }

    /**
     * Nothing has been collected or refunded at creation, and the CHECK constraints on those
     * columns are only meaningful if the aggregate starts them at zero rather than leaving them to
     * a default.
     */
    @Test
    void startsWithNothingCapturedAndNothingRefunded() {
        PaymentIntent intent = intent(MerchantId.generate(), null, 1999, "INR", null);

        assertEquals(0L, intent.capturedAmountMinor());
        assertEquals(0L, intent.refundedAmountMinor());
        assertNull(intent.failureCode());
        assertNull(intent.failureMessage());
        assertNull(intent.cancelledAt());
    }

    @Test
    void defaultsToAutomaticCapture() {
        assertEquals(
            CaptureMethod.AUTOMATIC,
            intent(MerchantId.generate(), null, 1999, "INR", null).captureMethod()
        );
    }

    @Test
    void keepsManualCaptureWhenItIsAsked() {
        assertEquals(
            CaptureMethod.MANUAL,
            intent(MerchantId.generate(), null, 1999, "INR", CaptureMethod.MANUAL).captureMethod()
        );
    }

    // --- normalization ---------------------------------------------------------

    @Test
    void uppercasesAndTrimsTheCurrency() {
        assertEquals("INR", intent(MerchantId.generate(), null, 1999, "  inr  ", null).currency());
    }

    @Test
    void trimsTheOrderIdentifier() {
        assertEquals(ORDER_ID, PaymentIntent.create(
            PaymentIntentId.generate(),
            MerchantId.generate(),
            "  " + ORDER_ID + "  ",
            null,
            1999,
            "INR",
            null,
            null,
            Map.of(),
            NOW
        ).orderId());
    }

    /**
     * Absent and whitespace-only mean the same thing, so both become null. Two spellings of "no
     * description" must not become two different rows.
     */
    @Test
    void turnsBlankOptionalTextIntoNull() {
        PaymentIntent intent = PaymentIntent.create(
            PaymentIntentId.generate(),
            MerchantId.generate(),
            ORDER_ID,
            "   ",
            1999,
            "INR",
            null,
            "   ",
            Map.of(),
            NOW
        );

        assertNull(intent.customerId());
        assertNull(intent.description());
    }

    // --- invariants ------------------------------------------------------------

    @Test
    void rejectsAnAmountThatIsNotPositive() {
        assertThrows(
            IllegalArgumentException.class,
            () -> intent(MerchantId.generate(), null, 0, "INR", null)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> intent(MerchantId.generate(), null, -1, "INR", null)
        );
    }

    /**
     * The ceiling is the same number Order enforces, restated rather than imported so Payment's
     * domain does not depend on Order's. If these two ever disagree, an order could exist that no
     * intent may collect.
     */
    @Test
    void rejectsAnAmountAboveTheSameCeilingOrderUses() {
        assertEquals(999_999_999_999L, PaymentIntent.MAX_AMOUNT_MINOR);

        assertThrows(
            IllegalArgumentException.class,
            () -> intent(MerchantId.generate(), null, PaymentIntent.MAX_AMOUNT_MINOR + 1, "INR", null)
        );
    }

    @Test
    void rejectsACurrencyThatIsNotThreeLetters() {
        assertThrows(
            IllegalArgumentException.class,
            () -> intent(MerchantId.generate(), null, 1999, "RUPEES", null)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> intent(MerchantId.generate(), null, 1999, "IN1", null)
        );
    }

    /** An intent with no order has no obligation to collect against. */
    @Test
    void rejectsAMissingOrderIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> PaymentIntent.create(
            PaymentIntentId.generate(),
            MerchantId.generate(),
            "   ",
            null,
            1999,
            "INR",
            null,
            null,
            Map.of(),
            NOW
        ));
    }

    @Test
    void rejectsMoreMetadataThanTheCap() {
        Map<String, String> tooMuch = new LinkedHashMap<>();

        for (int entry = 0; entry <= PaymentIntent.MAX_METADATA_ENTRIES; entry++) {
            tooMuch.put("key" + entry, "value");
        }

        assertThrows(IllegalArgumentException.class, () -> PaymentIntent.create(
            PaymentIntentId.generate(),
            MerchantId.generate(),
            ORDER_ID,
            null,
            1999,
            "INR",
            null,
            null,
            tooMuch,
            NOW
        ));
    }

    // --- attach ------------------------------------------------------------------

    @Test
    void attachesAPaymentMethodAndAwaitsConfirmation() {
        Instant attachedAt = NOW.plusSeconds(30);

        PaymentIntent attached = intent(MerchantId.generate(), null, 1999, "INR", null)
            .attach(PaymentMethodType.UPI, attachedAt);

        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, attached.status());
        assertEquals(PaymentMethodType.UPI, attached.paymentMethodType());
        assertEquals(attachedAt, attached.updatedAt());
        assertEquals(NOW, attached.createdAt());
    }

    /** Immutability: attaching produces a new aggregate and leaves the original untouched. */
    @Test
    void leavesTheOriginalIntentUnchangedWhenAMethodIsAttached() {
        PaymentIntent original = intent(MerchantId.generate(), null, 1999, "INR", null);

        PaymentIntent attached = original.attach(PaymentMethodType.CARD, NOW);

        assertNotSame(original, attached);
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, original.status());
        assertNull(original.paymentMethodType());
    }

    /**
     * RE-ATTACHING IS REFUSED, NOT QUIETLY ALLOWED. Past this point the intent may already have an
     * attempt in flight, and a stored method that disagrees with what the provider was actually
     * asked for is worse than no record. A merchant who chose wrongly cancels and starts again.
     */
    @Test
    void refusesToAttachASecondPaymentMethod() {
        PaymentIntent attached = intent(MerchantId.generate(), null, 1999, "INR", null)
            .attach(PaymentMethodType.CARD, NOW);

        assertThrows(
            PaymentMethodNotAttachableException.class,
            () -> attached.attach(PaymentMethodType.WALLET, NOW.plusSeconds(1))
        );
    }

    @Test
    void refusesToAttachToAProcessingIntent() {
        PaymentIntent processing = reconstitutedWith(PaymentIntentStatus.PROCESSING);

        assertThrows(
            PaymentMethodNotAttachableException.class,
            () -> processing.attach(PaymentMethodType.CARD, NOW)
        );
    }

    @Test
    void refusesToAttachToACancelledIntent() {
        PaymentIntent cancelled = intent(MerchantId.generate(), null, 1999, "INR", null)
            .cancel(null, NOW);

        assertThrows(
            PaymentMethodNotAttachableException.class,
            () -> cancelled.attach(PaymentMethodType.CARD, NOW.plusSeconds(1))
        );
    }

    @Test
    void rejectsAnAttachWithNoMethodTypeOrNoTimestamp() {
        PaymentIntent intent = intent(MerchantId.generate(), null, 1999, "INR", null);

        assertThrows(IllegalArgumentException.class, () -> intent.attach(null, NOW));
        assertThrows(
            IllegalArgumentException.class,
            () -> intent.attach(PaymentMethodType.CARD, null)
        );
    }

    // --- confirm ------------------------------------------------------------------

    @Test
    void confirmsAnIntentThatHasAMethodAttached() {
        Instant confirmedAt = NOW.plusSeconds(90);

        PaymentIntent processing = intent(MerchantId.generate(), null, 1999, "INR", null)
            .attach(PaymentMethodType.CARD, NOW.plusSeconds(30))
            .confirm(confirmedAt);

        assertEquals(PaymentIntentStatus.PROCESSING, processing.status());
        // The method survives the transition. An intent in PROCESSING with no method would violate
        // ck_payment_intents_method_known, and losing it here is the failure a hand-copied
        // constructor call produces.
        assertEquals(PaymentMethodType.CARD, processing.paymentMethodType());
        assertEquals(confirmedAt, processing.updatedAt());
        assertEquals(1999L, processing.amountMinor());
        assertEquals(0L, processing.capturedAmountMinor());
    }

    /**
     * ATTACH IS A GENUINE PREREQUISITE. Confirming without a method would have to pick an
     * instrument on the merchant's behalf, so it is refused rather than defaulted.
     */
    @Test
    void refusesToConfirmAnIntentWithNoPaymentMethod() {
        PaymentIntent intent = intent(MerchantId.generate(), null, 1999, "INR", null);

        assertThrows(PaymentIntentNotConfirmableException.class, () -> intent.confirm(NOW));
    }

    @Test
    void refusesToConfirmAnIntentThatIsAlreadyProcessing() {
        PaymentIntent processing = intent(MerchantId.generate(), null, 1999, "INR", null)
            .attach(PaymentMethodType.CARD, NOW)
            .confirm(NOW.plusSeconds(1));

        assertThrows(
            PaymentIntentNotConfirmableException.class,
            () -> processing.confirm(NOW.plusSeconds(2))
        );
    }

    @Test
    void refusesToConfirmACancelledIntent() {
        PaymentIntent cancelled = intent(MerchantId.generate(), null, 1999, "INR", null)
            .attach(PaymentMethodType.CARD, NOW)
            .cancel("changed mind", NOW.plusSeconds(1));

        assertThrows(
            PaymentIntentNotConfirmableException.class,
            () -> cancelled.confirm(NOW.plusSeconds(2))
        );
    }

    @Test
    void rejectsAConfirmationWithNoTimestamp() {
        PaymentIntent attached = intent(MerchantId.generate(), null, 1999, "INR", null)
            .attach(PaymentMethodType.CARD, NOW);

        assertThrows(IllegalArgumentException.class, () -> attached.confirm(null));
    }

    /**
     * THE STATES THIS PR DOES NOT OWN STAY UNREACHABLE. Neither transition can produce SUCCEEDED,
     * FAILED, AUTHORIZED or REQUIRES_ACTION -- those belong to provider callbacks -- and the only
     * forward move from PROCESSING is none at all.
     */
    @Test
    void reachesOnlyTheStatesThisStepOwns() {
        PaymentIntent intent = intent(MerchantId.generate(), null, 1999, "INR", null);
        PaymentIntent attached = intent.attach(PaymentMethodType.CARD, NOW);
        PaymentIntent processing = attached.confirm(NOW.plusSeconds(1));

        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, intent.status());
        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, attached.status());
        assertEquals(PaymentIntentStatus.PROCESSING, processing.status());

        assertThrows(
            PaymentIntentNotConfirmableException.class,
            () -> processing.confirm(NOW.plusSeconds(2))
        );
        assertThrows(
            PaymentMethodNotAttachableException.class,
            () -> processing.attach(PaymentMethodType.CARD, NOW.plusSeconds(2))
        );
        assertThrows(
            PaymentIntentNotCancellableException.class,
            () -> processing.cancel(null, NOW.plusSeconds(2))
        );
    }

    // --- cancellation ----------------------------------------------------------

    /**
     * THE SLOT-RELEASE ROUTE OUT OF REQUIRES_CONFIRMATION (ADR-011 section 3). Without it, a
     * merchant who attaches a method and then abandons the checkout has an intent that holds the
     * order's only slot forever, and a stuck intent is a stuck order.
     */
    @Test
    void cancelsAnIntentAwaitingConfirmation() {
        Instant cancelledAt = NOW.plusSeconds(60);

        PaymentIntent cancelled = intent(MerchantId.generate(), null, 1999, "INR", null)
            .attach(PaymentMethodType.NET_BANKING, NOW.plusSeconds(30))
            .cancel("abandoned the checkout", cancelledAt);

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.status());
        assertEquals("abandoned the checkout", cancelled.cancellationReason());
        assertEquals(cancelledAt, cancelled.cancelledAt());
        // The method it died holding, kept rather than cleared: what a merchant chose before
        // abandoning is exactly what an investigation into an abandoned checkout wants.
        assertEquals(PaymentMethodType.NET_BANKING, cancelled.paymentMethodType());
    }

    @Test
    void cancelsAnIntentThatIsStillAwaitingAPaymentMethod() {
        Instant cancelledAt = NOW.plusSeconds(60);

        PaymentIntent cancelled = intent(MerchantId.generate(), null, 1999, "INR", null)
            .cancel("  customer changed their mind  ", cancelledAt);

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.status());
        assertEquals("customer changed their mind", cancelled.cancellationReason());
        assertEquals(cancelledAt, cancelled.cancelledAt());
        assertEquals(cancelledAt, cancelled.updatedAt());
        assertEquals(NOW, cancelled.createdAt());
    }

    @Test
    void cancelsWithoutAReason() {
        PaymentIntent cancelled = intent(MerchantId.generate(), null, 1999, "INR", null)
            .cancel(null, NOW);

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.cancellationReason());
    }

    /**
     * The refusal is what makes a repeated cancel a 409 rather than a silent overwrite of the first
     * cancellation's timestamp and reason.
     */
    @Test
    void refusesToCancelAnIntentThatIsAlreadyCancelled() {
        PaymentIntent cancelled = intent(MerchantId.generate(), null, 1999, "INR", null)
            .cancel(null, NOW);

        assertThrows(
            PaymentIntentNotCancellableException.class,
            () -> cancelled.cancel(null, NOW.plusSeconds(1))
        );
    }

    /**
     * PROCESSING IS DELIBERATELY UNCANCELLABLE and must stay that way: an in-flight attempt may
     * already have succeeded at the provider, so a local cancel could erase a payment that really
     * happened. It is unreachable today, which is exactly why this needs asserting -- the rule has
     * to survive the PR that makes it reachable.
     */
    @Test
    void refusesToCancelAProcessingIntent() {
        PaymentIntent processing = reconstitutedWith(PaymentIntentStatus.PROCESSING);

        assertThrows(
            PaymentIntentNotCancellableException.class,
            () -> processing.cancel(null, NOW)
        );
    }

    @Test
    void refusesToCancelASucceededIntent() {
        PaymentIntent succeeded = reconstitutedWith(PaymentIntentStatus.SUCCEEDED);

        assertThrows(PaymentIntentNotCancellableException.class, () -> succeeded.cancel(null, NOW));
    }

    /** Immutability: cancelling produces a new aggregate and leaves the original untouched. */
    @Test
    void leavesTheOriginalIntentUnchangedWhenItIsCancelled() {
        PaymentIntent original = intent(MerchantId.generate(), null, 1999, "INR", null);

        PaymentIntent cancelled = original.cancel(null, NOW);

        assertNotSame(original, cancelled);
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, original.status());
        assertNull(original.cancelledAt());
    }

    @Test
    void rejectsACancellationWithNoTimestamp() {
        PaymentIntent intent = intent(MerchantId.generate(), null, 1999, "INR", null);

        assertThrows(IllegalArgumentException.class, () -> intent.cancel("why", null));
    }

    // --- reconstitution --------------------------------------------------------

    /**
     * Reconstitute restores whatever the row said, including states create can never produce.
     * Re-normalizing here would mask corruption rather than repair it.
     */
    @Test
    void restoresAnyPersistedStatusWithoutRenormalizing() {
        PaymentIntent restored = reconstitutedWith(PaymentIntentStatus.AUTHORIZED);

        assertEquals(PaymentIntentStatus.AUTHORIZED, restored.status());
        assertEquals(7, restored.version());
        assertTrue(restored.metadata().isEmpty());
    }

    private static PaymentIntent intent(
        MerchantId merchantId,
        String customerId,
        long amountMinor,
        String currency,
        CaptureMethod captureMethod
    ) {
        return PaymentIntent.create(
            PaymentIntentId.generate(),
            merchantId,
            ORDER_ID,
            customerId,
            amountMinor,
            currency,
            captureMethod,
            null,
            Map.of(),
            NOW
        );
    }

    // --- the provider transitions ---------------------------------------------------

    @Test
    void movesFromProcessingToEachOutcomeAProviderCanReport() {
        PaymentIntent processing = reconstitutedWith(PaymentIntentStatus.PROCESSING);

        assertEquals(
            PaymentIntentStatus.AUTHORIZED, processing.authorize(NOW).status()
        );
        assertEquals(
            PaymentIntentStatus.REQUIRES_ACTION, processing.requireAction(NOW).status()
        );
        assertEquals(
            PaymentIntentStatus.SUCCEEDED, processing.succeed(1999, NOW).status()
        );
        assertEquals(
            PaymentIntentStatus.FAILED, processing.fail("do_not_honour", "Declined", NOW).status()
        );
    }

    /**
     * A SUCCEEDED intent that captured nothing is a contradiction the schema also refuses
     * (ck_payment_intents_succeeded_captured), and a payment record that cannot say how much it
     * took is not a record.
     */
    @Test
    void recordsWhatWasCapturedWhenAPaymentSucceeds() {
        PaymentIntent succeeded = reconstitutedWith(PaymentIntentStatus.PROCESSING).succeed(1999, NOW);

        assertEquals(1999L, succeeded.capturedAmountMinor());
    }

    /** Authorizing holds funds and captures nothing. Writing the figure here would say otherwise. */
    @Test
    void capturesNothingWhenAPaymentIsMerelyAuthorized() {
        PaymentIntent authorized = reconstitutedWith(PaymentIntentStatus.PROCESSING).authorize(NOW);

        assertEquals(0L, authorized.capturedAmountMinor());
    }

    /** The backstop behind the service's amount check: a provider cannot change what is owed. */
    @Test
    void refusesToCaptureMoreThanTheIntentIsWorth() {
        PaymentIntent processing = reconstitutedWith(PaymentIntentStatus.PROCESSING);

        assertThrows(IllegalArgumentException.class, () -> processing.succeed(2000, NOW));
        assertThrows(IllegalArgumentException.class, () -> processing.succeed(0, NOW));
    }

    /**
     * TERMINAL STATES ABSORB, which is one of the two out-of-order mechanisms (ADR-012). A late
     * callback landing on a finished payment must change nothing -- and the callback path turns this
     * refusal into a 200 IGNORED_TERMINAL rather than a 409, because a provider retries on non-2xx.
     */
    @Test
    void refusesAProviderOutcomeFromEveryStateButProcessing() {
        for (PaymentIntentStatus status : PaymentIntentStatus.values()) {
            if (status == PaymentIntentStatus.PROCESSING) {
                continue;
            }

            PaymentIntent intent = reconstitutedWith(status);

            assertThrows(
                ProviderOutcomeNotApplicableException.class,
                () -> intent.succeed(1999, NOW),
                "SUCCEEDED must not be reachable from " + status
            );
            assertThrows(
                ProviderOutcomeNotApplicableException.class,
                () -> intent.authorize(NOW),
                "AUTHORIZED must not be reachable from " + status
            );
        }
    }

    /**
     * The 3DS loop. Confirming again from REQUIRES_ACTION is what makes the state machine cyclic,
     * and it is why the monotonic event clock exists: inside this cycle a stale REQUIRES_ACTION is a
     * LEGAL transition that the state machine cannot refuse on its own.
     */
    @Test
    void confirmsAgainFromRequiresAction() {
        PaymentIntent parked = reconstitutedWith(PaymentIntentStatus.REQUIRES_ACTION);

        assertEquals(PaymentIntentStatus.PROCESSING, parked.confirm(NOW).status());
    }

    /**
     * ADR-011's slot table requires this row. A customer who abandons a 3DS challenge is ordinary
     * behaviour; without a cancel the intent holds the order's only slot forever and the order is
     * dead in both directions.
     */
    @Test
    void cancelsAnIntentParkedAtRequiresAction() {
        PaymentIntent parked = reconstitutedWith(PaymentIntentStatus.REQUIRES_ACTION);

        assertEquals(PaymentIntentStatus.CANCELLED, parked.cancel("abandoned", NOW).status());
    }

    private static PaymentIntent reconstitutedWith(PaymentIntentStatus status) {
        return PaymentIntent.reconstitute(
            PaymentIntentId.generate(),
            MerchantId.generate(),
            ORDER_ID,
            null,
            1999,
            "INR",
            CaptureMethod.AUTOMATIC,
            // Past REQUIRES_PAYMENT_METHOD a method is always known, and
            // ck_payment_intents_method_known says so; CANCELLED is the one state that may have
            // reached the end without one, and null is the honest value for both.
            status == PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                || status == PaymentIntentStatus.CANCELLED
                ? null
                : PaymentMethodType.CARD,
            status,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            7,
            NOW,
            NOW
        );
    }

    // --- manual capture ------------------------------------------------------------------

    /** AUTHORIZED to SUCCEEDED. The one path to SUCCEEDED that no provider asked for. */
    @Test
    void capturesTheFullAuthorizedAmount() {
        PaymentIntent captured = authorizedManual().capture(1999, NOW.plusSeconds(60));

        assertEquals(PaymentIntentStatus.SUCCEEDED, captured.status());
        assertEquals(1999L, captured.capturedAmountMinor());
        assertEquals(1999L, captured.amountMinor());
        assertEquals(NOW.plusSeconds(60), captured.updatedAt());
    }

    /**
     * PARTIAL CAPTURE STILL SUCCEEDS, with captured below authorized. That gap is what makes
     * {@code orders.PARTIALLY_PAID} reachable later, and both CHECK constraints still hold on the
     * row it produces: captured > 0, and captured <= amount.
     */
    @Test
    void capturesLessThanAuthorizedAndStillReachesSucceeded() {
        PaymentIntent captured = authorizedManual().capture(500, NOW);

        assertEquals(PaymentIntentStatus.SUCCEEDED, captured.status());
        assertEquals(500L, captured.capturedAmountMinor());
        assertEquals(1999L, captured.amountMinor());
    }

    /** Overcapture is a number that must not exist. The CHECK says so too; this is the message. */
    @Test
    void refusesToCaptureMoreThanAuthorized() {
        assertThrows(
            CaptureAmountExceedsAuthorizedException.class,
            () -> authorizedManual().capture(2000, NOW)
        );
    }

    @Test
    void refusesToCaptureZeroOrLess() {
        assertThrows(IllegalArgumentException.class, () -> authorizedManual().capture(0, NOW));
        assertThrows(IllegalArgumentException.class, () -> authorizedManual().capture(-1, NOW));
    }

    @Test
    void refusesToCaptureWithNoTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> authorizedManual().capture(1999, null));
    }

    /**
     * AN AUTOMATIC INTENT IS THE PROVIDER'S TO CAPTURE. Allowing it here would put two collectors on
     * one authorization -- the merchant, and the SUCCEEDED callback on its way.
     */
    @Test
    void refusesToCaptureAnAutomaticIntent() {
        PaymentIntent automatic = intent(
            MerchantId.generate(), null, 1999, "INR", CaptureMethod.AUTOMATIC
        ).attach(PaymentMethodType.CARD, NOW).confirm(NOW).authorize(NOW);

        assertThrows(
            PaymentIntentNotCapturableException.class, () -> automatic.capture(1999, NOW)
        );
    }

    /** Capture is legal from AUTHORIZED and from nowhere else. */
    @Test
    void refusesToCaptureFromEveryOtherState() {
        for (PaymentIntentStatus status : PaymentIntentStatus.values()) {
            if (status == PaymentIntentStatus.AUTHORIZED) {
                continue;
            }

            assertThrows(
                PaymentIntentNotCapturableException.class,
                () -> reconstitutedWith(status).capture(1999, NOW),
                "capture must be refused from " + status
            );
        }
    }

    // --- AUTHORIZED joins the cancellable set (ADR-011's slot table) ----------------------

    /**
     * A MANUAL intent parked at AUTHORIZED is a state a customer can abandon indefinitely, and
     * ADR-011's slot table requires every such state to have a route to CANCELLED. Without it the
     * order's only slot is held forever by funds nobody intends to take.
     */
    @Test
    void cancelsAnAuthorizedIntentToReleaseTheOrdersSlot() {
        PaymentIntent cancelled = authorizedManual().cancel("out of stock", NOW.plusSeconds(60));

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.status());
        assertEquals("out of stock", cancelled.cancellationReason());
        assertEquals(NOW.plusSeconds(60), cancelled.cancelledAt());
        assertEquals(0L, cancelled.capturedAmountMinor());
    }

    /** Captured funds cannot be un-captured by cancelling. Refunding is the Refund capability's. */
    @Test
    void refusesToCancelACapturedIntent() {
        PaymentIntent captured = authorizedManual().capture(1999, NOW);

        assertThrows(
            PaymentIntentNotCancellableException.class, () -> captured.cancel("oops", NOW)
        );
    }

    /**
     * PROCESSING STAYS UNCANCELLABLE, and widening the set for AUTHORIZED must not have widened it
     * here by accident. An in-flight attempt may already have succeeded at the provider, so a local
     * cancel could erase a payment that really happened -- strictly worse than a stuck order
     * (ADR-011 section 5). The PROCESSING timeout is the exit, and it is a FAILED, not a cancel.
     */
    @Test
    void stillRefusesToCancelAProcessingIntent() {
        assertThrows(
            PaymentIntentNotCancellableException.class,
            () -> reconstitutedWith(PaymentIntentStatus.PROCESSING).cancel("give up", NOW)
        );
    }

    /**
     * THE TWO REFUND STATES STAY UNREACHABLE. There is no aggregate method that produces either, so
     * this loop is a standing check that one was not added: {@code capture} is the last transition
     * this design adds, and after it every other status has a path while these two must not.
     */
    @Test
    void reachesNoRefundStateFromAnyTransitionTheAggregateOffers() {
        PaymentIntent authorized = authorizedManual();

        assertEquals(PaymentIntentStatus.SUCCEEDED, authorized.capture(1999, NOW).status());
        assertEquals(PaymentIntentStatus.CANCELLED, authorized.cancel(null, NOW).status());
        assertEquals(
            PaymentIntentStatus.FAILED,
            reconstitutedWith(PaymentIntentStatus.PROCESSING).fail("x", "y", NOW).status()
        );
    }

    private static PaymentIntent authorizedManual() {
        return intent(MerchantId.generate(), null, 1999, "INR", CaptureMethod.MANUAL)
            .attach(PaymentMethodType.CARD, NOW)
            .confirm(NOW)
            .authorize(NOW);
    }
}
