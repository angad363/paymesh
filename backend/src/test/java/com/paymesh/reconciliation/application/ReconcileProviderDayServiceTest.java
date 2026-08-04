package com.paymesh.reconciliation.application;

import com.paymesh.reconciliation.application.ReconcileProviderDayService.ReconciliationResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What reconciliation decides a provider row MEANS, in plain JUnit with no HTTP and no database
 * (ADR-026).
 * <p>
 * Everything the job actually owns is here: which provider statuses are terminal, which amounts
 * belong on which outcome, how an event id is minted, and what happens to a row it cannot match.
 * What it deliberately does NOT own -- whether a payment may legally move -- lives in
 * {@code RecordProviderCallbackService} and is proved by the integration test.
 */
class ReconcileProviderDayServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-08-03");
    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant PROVIDER_TIME = Instant.parse("2026-08-03T14:30:00Z");

    private final RecordingPaymentRepair payments = new RecordingPaymentRepair();
    private final RecordingRefundRepair refunds = new RecordingRefundRepair();

    // --- WHAT A PROVIDER STATUS MEANS ------------------------------------------------------------

    /**
     * THE ROW THIS WHOLE JOB EXISTS FOR. The provider collected the money; PayMesh does not know it,
     * because ADR-015's sweeper timed the intent out to FAILED when no callback arrived. Replaying
     * SUCCEEDED is what puts the balance on the Ledger.
     * <p>
     * <b>Sabotage that must turn this red:</b> pass the captured amount on some other outcome, or
     * drop it. {@code RecordProviderCallbackService} refuses a SUCCEEDED whose amount does not match
     * the intent, so the repair would be recorded and never applied -- and the merchant would still
     * be short, with a row that looks like reconciliation ran.
     */
    @Test
    void replaysACapturedPaymentAsSucceededWithTheCapturedAmount() {
        payments.answer(RepairOutcome.REPAIRED);

        ReconciliationResult result = reconcile(payment("CAPTURED", 4000, 4000, null));

        assertThat(result).isEqualTo(new ReconciliationResult(1, 1, 0, 0));
        assertThat(payments.calls()).hasSize(1);

        RecordedPayment call = payments.calls().getFirst();
        assertThat(call.outcome()).isEqualTo(ReplayedOutcome.SUCCEEDED);
        assertThat(call.capturedAmountMinor()).isEqualTo(4000L);
        assertThat(call.authorizedAmountMinor())
            .as("an authorized amount on a SUCCEEDED row is a field the intent would refuse")
            .isNull();
    }

    @Test
    void replaysAnAuthorizedPaymentWithTheAuthorizedAmountAndNoCapture() {
        payments.answer(RepairOutcome.ALREADY_CONSISTENT);

        reconcile(payment("AUTHORIZED", 4000, 0, null));

        RecordedPayment call = payments.calls().getFirst();
        assertThat(call.outcome()).isEqualTo(ReplayedOutcome.AUTHORIZED);
        assertThat(call.authorizedAmountMinor()).isEqualTo(4000L);
        assertThat(call.capturedAmountMinor()).isNull();
    }

    /**
     * TIMED_OUT IS THE INTERESTING BUCKET, AND THE CLAIM IS NARROW. The provider decided and told
     * nobody, and its own record says nothing was collected. That turns ADR-015's guess into a
     * confirmation -- the sweeper failed the payment hoping it had not collected; the provider's file
     * says it did not.
     * <p>
     * The row carries no failure code, because the provider never articulated one. PayMesh supplies
     * its own so the resulting record says which mechanism concluded this, which is the first thing
     * anyone investigating it will want.
     */
    @Test
    void replaysATimedOutPaymentAsFailedWithAReconciliationFailureCode() {
        payments.answer(RepairOutcome.REPAIRED);

        reconcile(payment("TIMED_OUT", 4000, 0, null));

        RecordedPayment call = payments.calls().getFirst();
        assertThat(call.outcome()).isEqualTo(ReplayedOutcome.FAILED);
        assertThat(call.failureCode()).isEqualTo("provider_reported_no_collection");
        assertThat(call.capturedAmountMinor())
            .as("nothing was collected, so claiming an amount would be inventing one")
            .isNull();
    }

    /** A provider that DID articulate a reason keeps it; PayMesh does not overwrite the truth. */
    @Test
    void keepsTheProvidersOwnFailureCodeWhenItGaveOne() {
        payments.answer(RepairOutcome.REPAIRED);

        reconcile(payment("DECLINED", 4000, 0, "do_not_honour"));

        assertThat(payments.calls().getFirst().failureCode()).isEqualTo("do_not_honour");
    }

    /**
     * A STATUS THIS CODE HAS NEVER SEEN MUST DO NOTHING, NOT DEFAULT. Every value in that switch
     * moves money, so an unrecognised one falling through to any outcome would be the worst possible
     * failure -- a provider adding a status and PayMesh silently failing or collecting payments
     * because of it.
     * <p>
     * <b>Sabotage that must turn this red:</b> give {@code outcomeOf}'s {@code default} branch any
     * value at all.
     */
    @Test
    void ignoresAProviderStatusItDoesNotRecognise() {
        ReconciliationResult result = reconcile(payment("UNDER_REVIEW", 4000, 0, null));

        assertThat(payments.calls()).isEmpty();
        assertThat(result)
            .as("not examined, not unresolved -- there is no settled fact to act on")
            .isEqualTo(new ReconciliationResult(0, 0, 0, 0));
    }

    // --- ROWS THAT CANNOT BE MATCHED -------------------------------------------------------------

    /**
     * A payment PayMesh has no way to name is reported, never invented. Creating a local record from
     * a provider's file would be manufacturing money movement out of a document.
     */
    @Test
    void reportsAPaymentRowThatNamesNothingPayMeshCanResolve() {
        ReconciliationResult result = reconcile(new ProviderDayReport.Payment(
            null, null, "CAPTURED", 4000, 4000, null, null, PROVIDER_TIME
        ));

        assertThat(payments.calls()).isEmpty();
        assertThat(result).isEqualTo(new ReconciliationResult(1, 0, 1, 0));
    }

    /**
     * A refund is resolvable ONLY by the reference PayMesh supplied -- there is no fallback, because
     * Refund's callback route identifies a refund by PayMesh's own id and the provider's refund id
     * was never recorded against it. Rows written before ADR-026 have none.
     */
    @Test
    void reportsARefundRowWrittenBeforeItCarriedAReference() {
        ReconciliationResult result = reconcileRefund(new ProviderDayReport.Refund(
            null, "prf_1", "pay_1", "SUCCEEDED", 1500, null, null, PROVIDER_TIME
        ));

        assertThat(refunds.calls()).isEmpty();
        assertThat(result).isEqualTo(new ReconciliationResult(1, 0, 1, 0));
    }

    // --- ISOLATION -------------------------------------------------------------------------------

    /**
     * ONE UNREPAIRABLE ROW MUST NOT DISABLE THE PASS. This is the failure shape open item 2 records
     * in two other sweeps: work outside the per-item try, so one bad row throws out of the whole run
     * and -- because the order is stable -- keeps doing so on every subsequent run.
     */
    @Test
    void keepsReconcilingAfterOneRowThrows() {
        payments.throwFor("pay_poison", new IllegalStateException("this one is broken"));
        payments.answer(RepairOutcome.REPAIRED);

        ReconciliationResult result = reconcile(
            payment("pi_poison", "pay_poison", "CAPTURED", 4000, 4000, null),
            payment("pi_healthy", "pay_healthy", "CAPTURED", 2500, 2500, null)
        );

        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.repaired())
            .as("the healthy row behind the broken one must still be repaired")
            .isEqualTo(1);
    }

    /**
     * AN UNREACHABLE PROVIDER IS NOT A CLEAN RECONCILIATION, and this is the one failure that is
     * allowed to propagate. Swallowing it would produce zero examined and zero repaired -- identical
     * to a quiet day -- so a provider that has been down for a week would report success every night
     * while divergences piled up.
     */
    @Test
    void refusesToReportSuccessWhenTheProviderCouldNotBeRead() {
        ReconcileProviderDayService service = new ReconcileProviderDayService(
            date -> {
                throw new ProviderReportUnavailableException(date, "connection refused", null);
            },
            payments, refunds, 1, CLOCK
        );

        assertThatThrownBy(() -> service.reconcile(DAY))
            .isInstanceOf(ProviderReportUnavailableException.class);
    }

    // --- THE EVENT ID, WHICH IS THE WHOLE SAFETY ARGUMENT ----------------------------------------

    /**
     * RE-RUNNING A DAY MUST MINT THE SAME ID. The schedule reconciles the same recent days every
     * pass and an operator will re-run one by hand. Without determinism each run would look like a
     * new provider event and be applied again -- on the capture path, collecting twice.
     * <p>
     * <b>Sabotage that must turn this red:</b> put the clock, a UUID or the report date into
     * {@code eventIdFor}.
     */
    @Test
    void mintsTheSameEventIdWhenTheSameDayIsReconciledTwice() {
        payments.answer(RepairOutcome.REPAIRED);

        reconcile(payment("CAPTURED", 4000, 4000, null));
        reconcile(payment("CAPTURED", 4000, 4000, null));

        assertThat(payments.calls()).hasSize(2);
        assertThat(payments.calls().get(0).eventId()).isEqualTo(payments.calls().get(1).eventId());
    }

    /**
     * A ROW THAT MOVED ON SINCE THE LAST RUN MUST MINT A DIFFERENT ID. An authorization captured
     * after yesterday's reconciliation is a NEW fact; if it hashed to the same id it would be
     * swallowed as a duplicate and the capture would never be applied.
     */
    @Test
    void mintsADifferentEventIdWhenTheRowsTerminalStateChanged() {
        payments.answer(RepairOutcome.ALREADY_CONSISTENT);

        reconcile(payment("AUTHORIZED", 4000, 0, null));
        reconcile(payment("CAPTURED", 4000, 4000, null));

        assertThat(payments.calls().get(0).eventId())
            .isNotEqualTo(payments.calls().get(1).eventId());
    }

    /**
     * THE PREFIX IS NOT COSMETIC. If a minted id could collide with the id of a callback that DID
     * arrive, the replay would vanish into the dedup table and a real divergence would go unrepaired
     * -- silently, looking exactly like success.
     */
    @Test
    void marksItsEventIdsAsItsOwnAndKeepsThemWithinTheProviderEventLimit() {
        payments.answer(RepairOutcome.REPAIRED);

        reconcile(payment("CAPTURED", 4000, 4000, null));

        String eventId = payments.calls().getFirst().eventId();
        assertThat(eventId).startsWith("recon:");
        assertThat(eventId.length())
            .as("ProviderEvent caps an event id at 120 characters, and a truncated key collides")
            .isLessThanOrEqualTo(120);
    }

    /**
     * The PROVIDER's timestamp travels, never the job's clock. It is what the monotonic guard
     * compares, so stamping "now" would let a reconciliation of an old day overwrite a newer
     * callback -- dragging a payment backwards on the strength of a stale file.
     */
    @Test
    void carriesTheProvidersOwnTimestampRatherThanTheJobsClock() {
        payments.answer(RepairOutcome.REPAIRED);

        reconcile(payment("CAPTURED", 4000, 4000, null));

        assertThat(payments.calls().getFirst().occurredAt()).isEqualTo(PROVIDER_TIME);
        assertThat(payments.calls().getFirst().occurredAt()).isNotEqualTo(NOW);
    }

    // --- THE WINDOW ------------------------------------------------------------------------------

    /**
     * The lookback covers today and the days before it, oldest first, so a pass that did not run
     * does not skip its day forever.
     */
    @Test
    void reconcilesTheWholeLookbackWindowOldestDayFirst() {
        List<LocalDate> fetched = new ArrayList<>();

        new ReconcileProviderDayService(
            date -> {
                fetched.add(date);
                return new ProviderDayReport(date, List.of(), List.of());
            },
            payments, refunds, 3, CLOCK
        ).reconcile();

        assertThat(fetched).containsExactly(
            LocalDate.parse("2026-08-02"), LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-04")
        );
    }

    /** Today alone is the one window guaranteed to be incomplete, so it is refused. */
    @Test
    void refusesALookbackThatCoversNoDay() {
        assertThatThrownBy(() -> new ReconcileProviderDayService(
            date -> new ProviderDayReport(date, List.of(), List.of()), payments, refunds, 0, CLOCK
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private ReconciliationResult reconcile(ProviderDayReport.Payment... rows) {
        return serviceFor(new ProviderDayReport(DAY, List.of(rows), List.of())).reconcile(DAY);
    }

    private ReconciliationResult reconcileRefund(ProviderDayReport.Refund row) {
        return serviceFor(new ProviderDayReport(DAY, List.of(), List.of(row))).reconcile(DAY);
    }

    private ReconcileProviderDayService serviceFor(ProviderDayReport report) {
        return new ReconcileProviderDayService(date -> report, payments, refunds, 1, CLOCK);
    }

    private static ProviderDayReport.Payment payment(
        String status, long amountMinor, long capturedMinor, String failureCode
    ) {
        return payment("pi_1", "pay_1", status, amountMinor, capturedMinor, failureCode);
    }

    private static ProviderDayReport.Payment payment(
        String intentId,
        String providerPaymentId,
        String status,
        long amountMinor,
        long capturedMinor,
        String failureCode
    ) {
        return new ProviderDayReport.Payment(
            intentId, providerPaymentId, status, amountMinor, capturedMinor,
            failureCode, failureCode == null ? null : "the issuer said no", PROVIDER_TIME
        );
    }

    /** Records every replay it is asked for, so the test can assert on the CLAIM, not on a mock. */
    private static final class RecordingPaymentRepair implements PaymentRepair {

        private final List<RecordedPayment> calls = new ArrayList<>();
        private final Map<String, RuntimeException> failures = new java.util.HashMap<>();
        private RepairOutcome answer = RepairOutcome.ALREADY_CONSISTENT;

        void answer(RepairOutcome outcome) {
            this.answer = outcome;
        }

        void throwFor(String providerReference, RuntimeException failure) {
            failures.put(providerReference, failure);
        }

        List<RecordedPayment> calls() {
            return calls;
        }

        @Override
        public RepairOutcome replay(
            String paymentIntentId,
            String providerReference,
            ReplayedOutcome outcome,
            Long authorizedAmountMinor,
            Long capturedAmountMinor,
            String failureCode,
            String failureMessage,
            String eventId,
            Instant occurredAt
        ) {
            RuntimeException failure = failures.get(providerReference);

            if (failure != null) {
                throw failure;
            }

            calls.add(new RecordedPayment(
                paymentIntentId, outcome, authorizedAmountMinor, capturedAmountMinor,
                failureCode, eventId, occurredAt
            ));

            return answer;
        }
    }

    private record RecordedPayment(
        String paymentIntentId,
        ReplayedOutcome outcome,
        Long authorizedAmountMinor,
        Long capturedAmountMinor,
        String failureCode,
        String eventId,
        Instant occurredAt
    ) {
    }

    private static final class RecordingRefundRepair implements RefundRepair {

        private final List<String> calls = new ArrayList<>();

        List<String> calls() {
            return calls;
        }

        @Override
        public RepairOutcome replay(
            String refundId,
            String providerReference,
            boolean succeeded,
            String failureCode,
            String failureMessage,
            String eventId,
            Instant occurredAt
        ) {
            calls.add(refundId);

            return RepairOutcome.ALREADY_CONSISTENT;
        }
    }
}
