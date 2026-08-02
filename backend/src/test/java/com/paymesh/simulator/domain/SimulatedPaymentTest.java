package com.paymesh.simulator.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider's state machine, tested as ordinary Java with no Spring context -- it is a plain
 * final class and nothing here needs one.
 */
class SimulatedPaymentTest {

    private static final Instant NOW = Instant.parse("2026-08-02T11:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void capturesImmediatelyWhenTheProviderCapturesAutomatically() {
        SimulatedPayment payment = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.AUTOMATIC);

        assertThat(payment.status()).isEqualTo(SimulatedPaymentStatus.CAPTURED);
        assertThat(payment.capturedAmountMinor()).isEqualTo(1999);
    }

    /**
     * MANUAL stops at AUTHORIZED and captures nothing, which is what makes PayMesh's manual-capture
     * path reachable. ADR-012 section 4 refuses AUTHORIZED -> SUCCEEDED as a <em>provider</em>
     * transition, so a simulator that captured a MANUAL payment on its own say-so would produce an
     * IGNORED_TERMINAL and look broken.
     */
    @Test
    void stopsAtAuthorizedWhenTheProviderWaitsToBeAsked() {
        SimulatedPayment payment = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.MANUAL);

        assertThat(payment.status()).isEqualTo(SimulatedPaymentStatus.AUTHORIZED);
        assertThat(payment.capturedAmountMinor()).isZero();
    }

    @Test
    void declinesWithTheVocabularyACardNetworkUses() {
        SimulatedPayment payment = authorize(SimulatedBehaviour.DECLINE, SimulatedCaptureMethod.AUTOMATIC);

        assertThat(payment.status()).isEqualTo(SimulatedPaymentStatus.DECLINED);
        assertThat(payment.failureCode()).isEqualTo("do_not_honour");
        assertThat(payment.failureMessage()).isEqualTo(SimulatedPayment.DECLINE_MESSAGE);
        assertThat(payment.capturedAmountMinor()).isZero();
    }

    @Test
    void requiresActionForAThreeDomainSecureChallenge() {
        SimulatedPayment payment = authorize(SimulatedBehaviour.REQUIRE_ACTION, SimulatedCaptureMethod.AUTOMATIC);

        assertThat(payment.status()).isEqualTo(SimulatedPaymentStatus.REQUIRES_ACTION);
    }

    /**
     * TIMED_OUT is the provider having decided and never reported. The payment is real on this side
     * and invisible on PayMesh's, which is precisely the state ADR-015's sweeper exists for.
     */
    @Test
    void timesOutWithoutCapturingAnything() {
        SimulatedPayment payment = authorize(SimulatedBehaviour.TIMEOUT, SimulatedCaptureMethod.AUTOMATIC);

        assertThat(payment.status()).isEqualTo(SimulatedPaymentStatus.TIMED_OUT);
        assertThat(payment.capturedAmountMinor()).isZero();
        assertThat(payment.failureCode()).isNull();
    }

    /**
     * Both delivery-shaped behaviours ignore the capture method on purpose: they exercise PayMesh's
     * redelivery handling, and combining that with a two-step capture would make a failure ambiguous
     * about which half broke.
     */
    @Test
    void treatsTheDeliveryBehavioursAsAnAutomaticSuccessWhateverTheCaptureMethod() {
        assertThat(authorize(SimulatedBehaviour.DUPLICATE_CALLBACK, SimulatedCaptureMethod.MANUAL).status())
            .isEqualTo(SimulatedPaymentStatus.CAPTURED);
        assertThat(authorize(SimulatedBehaviour.STALE_CALLBACK, SimulatedCaptureMethod.MANUAL).status())
            .isEqualTo(SimulatedPaymentStatus.CAPTURED);
    }

    @Test
    void capturesAnAuthorizationInFull() {
        SimulatedPayment captured = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.MANUAL)
            .capture(1999, NOW.plusSeconds(60));

        assertThat(captured.status()).isEqualTo(SimulatedPaymentStatus.CAPTURED);
        assertThat(captured.capturedAmountMinor()).isEqualTo(1999);
        assertThat(captured.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void capturesLessThanWasAuthorized() {
        SimulatedPayment captured = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.MANUAL)
            .capture(500, NOW);

        assertThat(captured.capturedAmountMinor()).isEqualTo(500);
        assertThat(captured.amountMinor()).isEqualTo(1999);
    }

    /**
     * THE ONE THAT WOULD COLLECT TWICE. Refused here rather than left to the CHECK constraint, so the
     * caller gets a 409 naming the state instead of a 500 naming a PostgreSQL constraint.
     */
    @Test
    void refusesASecondCaptureOfAnAlreadyCapturedPayment() {
        SimulatedPayment captured = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.MANUAL)
            .capture(1999, NOW);

        assertThatThrownBy(() -> captured.capture(1999, NOW))
            .isInstanceOf(SimulatedPaymentNotCapturableException.class);
    }

    @Test
    void refusesToCaptureAPaymentTheIssuerDeclined() {
        SimulatedPayment declined = authorize(SimulatedBehaviour.DECLINE, SimulatedCaptureMethod.MANUAL);

        assertThatThrownBy(() -> declined.capture(1999, NOW))
            .isInstanceOf(SimulatedPaymentNotCapturableException.class);
    }

    @Test
    void refusesToCaptureMoreThanWasAuthorized() {
        SimulatedPayment authorized = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.MANUAL);

        assertThatThrownBy(() -> authorized.capture(2000, NOW))
            .isInstanceOf(CaptureExceedsAuthorizedAmountException.class);
    }

    @Test
    void refusesToCaptureANonPositiveAmount() {
        SimulatedPayment authorized = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.MANUAL);

        assertThatThrownBy(() -> authorized.capture(0, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsARefundAgainstWhatWasCaptured() {
        SimulatedPayment refunded = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.AUTOMATIC)
            .recordRefund(999, NOW);

        assertThat(refunded.refundedAmountMinor()).isEqualTo(999);
        assertThat(refunded.refundableAmountMinor()).isEqualTo(1000);
        assertThat(refunded.status()).isEqualTo(SimulatedPaymentStatus.CAPTURED);
    }

    @Test
    void refusesToRefundMoreThanRemains() {
        SimulatedPayment refunded = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.AUTOMATIC)
            .recordRefund(1000, NOW);

        assertThatThrownBy(() -> refunded.recordRefund(1000, NOW))
            .isInstanceOf(RefundExceedsCapturedAmountException.class);
    }

    /**
     * An AUTHORIZED payment has captured nothing, so there is nothing to give back. Without this the
     * refundable balance would be read off {@code amountMinor} and the provider would return money it
     * never took.
     */
    @Test
    void refusesToRefundAPaymentThatCapturedNothing() {
        SimulatedPayment authorized = authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.MANUAL);

        assertThat(authorized.refundableAmountMinor()).isZero();
        assertThatThrownBy(() -> authorized.recordRefund(1, NOW))
            .isInstanceOf(RefundExceedsCapturedAmountException.class);
    }

    @Test
    void normalizesTheCurrencyToUpperCase() {
        assertThat(authorize(SimulatedBehaviour.SUCCEED, SimulatedCaptureMethod.AUTOMATIC).currency())
            .isEqualTo("INR");
    }

    @Test
    void rejectsANonPositiveAmount() {
        assertThatThrownBy(() -> SimulatedPayment.authorize(
            SimulatedPaymentId.generate(), "key", HASH, "pi_ref", SimulatedMethod.CARD,
            "tok_sim_success", SimulatedBehaviour.SUCCEED, 0, "INR",
            SimulatedCaptureMethod.AUTOMATIC, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankCallbackReference() {
        assertThatThrownBy(() -> SimulatedPayment.authorize(
            SimulatedPaymentId.generate(), "key", HASH, "  ", SimulatedMethod.CARD,
            "tok_sim_success", SimulatedBehaviour.SUCCEED, 1999, "INR",
            SimulatedCaptureMethod.AUTOMATIC, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The mapper's entry point must return exactly what was stored. Re-deriving the status from the
     * behaviour on read would silently undo a capture on every load.
     */
    @Test
    void rehydratesTheStoredStatusRatherThanRederivingItFromTheBehaviour() {
        SimulatedPayment stored = SimulatedPayment.rehydrate(
            SimulatedPaymentId.generate(), "key", HASH, "pi_ref", SimulatedMethod.CARD,
            "tok_sim_success", SimulatedBehaviour.SUCCEED, 1999, "INR",
            SimulatedCaptureMethod.MANUAL, SimulatedPaymentStatus.CAPTURED, 1999, 0,
            null, null, NOW, NOW
        );

        assertThat(stored.status()).isEqualTo(SimulatedPaymentStatus.CAPTURED);
        assertThat(stored.capturedAmountMinor()).isEqualTo(1999);
    }

    private static SimulatedPayment authorize(
        SimulatedBehaviour behaviour,
        SimulatedCaptureMethod captureMethod
    ) {
        return SimulatedPayment.authorize(
            SimulatedPaymentId.generate(),
            "idem-" + behaviour + "-" + captureMethod,
            HASH,
            "pi_reference",
            SimulatedMethod.CARD,
            "tok_sim_success",
            behaviour,
            1999,
            "inr",
            captureMethod,
            NOW
        );
    }
}
