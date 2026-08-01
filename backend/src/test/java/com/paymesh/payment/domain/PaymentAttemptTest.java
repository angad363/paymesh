package com.paymesh.payment.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentAttemptTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");
    private static final String ORDER_ID = "ord_11111111-1111-4111-8111-111111111111";

    @Test
    void opensAnAttemptAgainstAConfirmedIntent() {
        PaymentIntent intent = confirmable();

        PaymentAttempt attempt = PaymentAttempt.start(
            PaymentAttemptId.generate(), intent, 1, null, null, NOW
        );

        assertEquals(intent.merchantId(), attempt.merchantId());
        assertEquals(intent.paymentIntentId(), attempt.paymentIntentId());
        assertEquals(1, attempt.attemptNumber());
        assertEquals(PaymentAttempt.SIMULATOR, attempt.provider());
        assertEquals(PaymentAttemptStatus.PROCESSING, attempt.status());
        assertEquals(NOW, attempt.createdAt());
        assertEquals(NOW, attempt.updatedAt());
        assertTrue(attempt.paymentAttemptId().value().startsWith("pat_"));
    }

    /**
     * THE MONEY IS COPIED FROM THE INTENT, NOT SUPPLIED. That is why start takes the aggregate: an
     * attempt for a figure the intent never carried would be an unexplainable row on the day someone
     * reconciles the two, and no caller has anything to get wrong here.
     */
    @Test
    void copiesTheMoneyFromTheIntent() {
        PaymentAttempt attempt = PaymentAttempt.start(
            PaymentAttemptId.generate(), confirmable(), 1, null, null, NOW
        );

        assertEquals(1999L, attempt.amountMinor());
        assertEquals("INR", attempt.currency());
    }

    /**
     * REDACTION. A merchant's return URL routinely carries a session token or a signed parameter in
     * its query, and this row is a durable audit record. The origin and path -- everything an
     * investigation needs -- survive; the credential does not.
     */
    @Test
    void stripsTheQueryAndFragmentFromTheReturnUrl() {
        PaymentAttempt attempt = PaymentAttempt.start(
            PaymentAttemptId.generate(),
            confirmable(),
            1,
            "https://shop.test/checkout/return?session=SECRET&cart=42#done",
            "web",
            NOW
        );

        assertEquals(
            Map.of("returnUrl", "https://shop.test/checkout/return", "device", "web"),
            attempt.requestPayload()
        );
    }

    /** An unparseable or non-absolute URL is dropped rather than stored: it cannot be redacted. */
    @Test
    void dropsAReturnUrlItCannotRedact() {
        PaymentAttempt attempt = PaymentAttempt.start(
            PaymentAttemptId.generate(), confirmable(), 1, "not a url at all", null, NOW
        );

        assertTrue(attempt.requestPayload().isEmpty());
    }

    /** Absent and blank mean the same thing, and neither becomes a key in the stored payload. */
    @Test
    void storesNothingWhenNeitherFieldWasSent() {
        PaymentAttempt attempt = PaymentAttempt.start(
            PaymentAttemptId.generate(), confirmable(), 1, "   ", null, NOW
        );

        assertTrue(attempt.requestPayload().isEmpty());
    }

    @Test
    void rejectsAnAttemptNumberBelowOne() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentAttempt.start(PaymentAttemptId.generate(), confirmable(), 0, null, null, NOW)
        );
    }

    @Test
    void rejectsAnAttemptWithNoIdentifierIntentOrTimestamp() {
        PaymentIntent intent = confirmable();

        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentAttempt.start(null, intent, 1, null, null, NOW)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentAttempt.start(PaymentAttemptId.generate(), null, 1, null, null, NOW)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentAttempt.start(PaymentAttemptId.generate(), intent, 1, null, null, null)
        );
    }

    @Test
    void rejectsAReturnUrlLongerThanTheColumn() {
        String tooLong = "https://shop.test/" + "x".repeat(500);

        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentAttempt.start(
                PaymentAttemptId.generate(), confirmable(), 1, tooLong, null, NOW
            )
        );
    }

    /**
     * The identifier's own rules, kept here rather than in a class of their own: it is
     * {@code PaymentIntentId} with a different prefix, and the interesting claim is that the prefix
     * really is different and really is checked.
     */
    @Test
    void mintsAndParsesOnlyPatPrefixedIdentifiers() {
        PaymentAttemptId minted = PaymentAttemptId.generate();

        assertEquals(minted, PaymentAttemptId.from(minted.value()));
        assertThrows(IllegalArgumentException.class, () -> PaymentAttemptId.from(null));
        assertThrows(
            IllegalArgumentException.class,
            () -> PaymentAttemptId.from("pi_11111111-1111-4111-8111-111111111111")
        );
        assertThrows(IllegalArgumentException.class, () -> PaymentAttemptId.from("pat_nope"));
    }

    private static PaymentIntent confirmable() {
        return PaymentIntent.create(
            PaymentIntentId.generate(),
            MerchantId.generate(),
            ORDER_ID,
            null,
            1999,
            "INR",
            null,
            null,
            Map.of(),
            NOW
        ).attach(PaymentMethodType.CARD, NOW).confirm(NOW);
    }
}
