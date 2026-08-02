package com.paymesh.refund.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The refund state machine, in plain JUnit -- no Spring, no database. */
class RefundTest {

    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final String PAYMENT = "pi_00000000-0000-0000-0000-000000000001";
    private static final Instant CREATED_AT = Instant.parse("2026-08-02T11:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-02T11:05:00Z");

    @Test
    void startsPendingSoThereIsSomethingToCancel() {
        assertThat(requested(99900).status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void movesToProcessingWhenSubmitted() {
        assertThat(requested(99900).submit(LATER).status()).isEqualTo(RefundStatus.PROCESSING);
    }

    @Test
    void recordsTheProviderReferenceOnSuccess() {
        Refund succeeded = requested(99900).submit(LATER).succeed("prov_re_1", LATER);

        assertThat(succeeded.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(succeeded.providerReference()).isEqualTo("prov_re_1");
        assertThat(succeeded.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void recordsTheProvidersReasonOnFailure() {
        Refund failed = requested(99900).submit(LATER).fail("declined", "Issuer said no", LATER);

        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("declined");
    }

    // --- what the state machine refuses ------------------------------------------------------

    /**
     * A SUCCESS FOR A REFUND NOBODY SUBMITTED. Not a theoretical case: it is what a forged or
     * misrouted callback looks like, and accepting it would complete a refund PayMesh never asked
     * the provider to perform.
     */
    @Test
    void refusesASuccessForARefundThatWasNeverSubmitted() {
        assertThatThrownBy(() -> requested(99900).succeed("prov_re_1", LATER))
            .isInstanceOf(RefundNotInStateException.class)
            .hasMessageContaining("PENDING");
    }

    @Test
    void refusesASecondOutcomeOnceTerminal() {
        Refund succeeded = requested(99900).submit(LATER).succeed(null, LATER);

        assertThatThrownBy(() -> succeeded.fail("late", "too late", LATER))
            .isInstanceOf(RefundNotInStateException.class);
    }

    /**
     * PROCESSING MEANS THE PROVIDER MAY ALREADY HAVE MOVED THE MONEY. Reporting CANCELLED then
     * would be PayMesh's opinion contradicting the customer's bank statement.
     */
    @Test
    void refusesToCancelOnceTheProviderHasIt() {
        Refund processing = requested(99900).submit(LATER);

        assertThatThrownBy(() -> processing.cancel(LATER))
            .isInstanceOf(RefundNotInStateException.class)
            .hasMessageContaining("PROCESSING");
    }

    @Test
    void cancelsWhileStillPending() {
        assertThat(requested(99900).cancel(LATER).status()).isEqualTo(RefundStatus.CANCELLED);
    }

    // --- what counts against the captured amount ----------------------------------------------

    /**
     * MIRRORS {@code tr_refunds_within_captured}'s {@code NOT IN ('FAILED','CANCELLED')} EXACTLY.
     * <p>
     * If this list and the trigger's ever disagree, a merchant gets a constraint violation where
     * the service promised them head-room -- or worse, the service allows what the trigger would
     * have caught and the over-refund lands.
     */
    @Test
    void countsEverythingExceptFailedAndCancelled() {
        assertThat(RefundStatus.PENDING.countsAgainstCapturedAmount()).isTrue();
        assertThat(RefundStatus.PROCESSING.countsAgainstCapturedAmount()).isTrue();
        assertThat(RefundStatus.SUCCEEDED.countsAgainstCapturedAmount()).isTrue();
        assertThat(RefundStatus.FAILED.countsAgainstCapturedAmount()).isFalse();
        assertThat(RefundStatus.CANCELLED.countsAgainstCapturedAmount()).isFalse();
    }

    // --- invariants ----------------------------------------------------------------------------

    @Test
    void refusesAZeroAmount() {
        assertThatThrownBy(() -> requested(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void refusesANegativeAmount() {
        assertThatThrownBy(() -> requested(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void uppercasesTheCurrency() {
        Refund refund = Refund.request(
            RefundId.generate(), MERCHANT, PAYMENT, 100, " inr ", null, null, CREATED_AT
        );

        assertThat(refund.currency()).isEqualTo("INR");
    }

    /** The amount is fixed at creation; every transition copies it through. */
    @Test
    void neverChangesTheAmountAcrossTransitions() {
        Refund refund = requested(99900);

        assertThat(refund.submit(LATER).amountMinor()).isEqualTo(99900);
        assertThat(refund.submit(LATER).succeed(null, LATER).amountMinor()).isEqualTo(99900);
        assertThat(refund.submit(LATER).fail("x", "y", LATER).amountMinor()).isEqualTo(99900);
        assertThat(refund.cancel(LATER).amountMinor()).isEqualTo(99900);
    }

    @Test
    void keepsTheCreationInstantAcrossTransitions() {
        assertThat(requested(99900).submit(LATER).createdAt()).isEqualTo(CREATED_AT);
    }

    private static Refund requested(long amountMinor) {
        return Refund.request(
            RefundId.generate(), MERCHANT, PAYMENT, amountMinor, "INR", null, null, CREATED_AT
        );
    }
}
