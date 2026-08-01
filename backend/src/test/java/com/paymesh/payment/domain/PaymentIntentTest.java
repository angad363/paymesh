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

    // --- cancellation ----------------------------------------------------------

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

    private static PaymentIntent reconstitutedWith(PaymentIntentStatus status) {
        return PaymentIntent.reconstitute(
            PaymentIntentId.generate(),
            MerchantId.generate(),
            ORDER_ID,
            null,
            1999,
            "INR",
            CaptureMethod.AUTOMATIC,
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
}
