package com.paymesh.reconciliation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Reads the provider's own truth for one day and repairs whatever PayMesh got wrong (ADR-026).
 *
 * <h2>THE JOB ADR-015 NAMED AND NOBODY WROTE</h2>
 *
 * {@code GET /sim/v1/reconciliation/{date}} has existed since ADR-017 and <b>nothing read it</b>.
 * Its own javadoc says so: "This is the input. It is not the job." Three separate places lean on the
 * job that was missing --
 * <ul>
 *   <li>ADR-015 times a stranded PROCESSING payment out to FAILED <b>with no evidence the payment
 *       failed</b>, and names reconciliation as the mitigation. Until now the guess was final: a
 *       payment the provider had actually collected stayed FAILED forever, the Ledger never posted
 *       it, and the merchant was simply short.</li>
 *   <li>ADR-023 does the same for refunds, and its javadoc says outright that the real answer "does
 *       not exist yet".</li>
 *   <li>ADR-019 lists a lost refund callback as Refund's known gap.</li>
 * </ul>
 * This closes all three, in the only way that does not build a second state machine.
 *
 * <h2>IT REPLAYS. IT DOES NOT DIFF.</h2>
 *
 * There is no comparison against PayMesh's state anywhere in this class, and that is the central
 * decision rather than an omission. Every terminal provider row is replayed through the same
 * callback service a real callback goes through, and the aggregate answers. So the amount check, the
 * staleness guard, the refusal of AUTHORIZED to SUCCEEDED, the attempt row and the outbox event that
 * makes the Ledger post are all the existing ones. A job that re-derived any of that would be a
 * second, quietly diverging copy of the transition rules on the money path.
 * <p>
 * What that costs: every row is replayed on every run, including the overwhelming majority that were
 * already correct. Each of those is one indexed lookup answering {@code DUPLICATE}. Cheap enough
 * that correctness-by-reuse is the better trade, and the bound is the day, not the table.
 *
 * <h2>THE EVENT ID IS THE WHOLE SAFETY ARGUMENT, AND IT HAS TWO HALVES</h2>
 *
 * Deduplication is {@code (provider, eventId)}. This job mints its own, and both properties matter:
 * <ul>
 *   <li><b>Deterministic</b>, derived from the row's identity AND its terminal values. Re-running a
 *       day -- which an operator will do, and the schedule does every night for the same recent
 *       days -- is therefore a {@code DUPLICATE}, not a second application. Without this a nightly
 *       job would re-apply the same outcome indefinitely.</li>
 *   <li><b>Distinct from the provider's own event ids</b>, via the {@code recon:} prefix. If it
 *       collided with the id of a callback that DID arrive, the replay would be swallowed as a
 *       duplicate and a genuine divergence would go unrepaired -- the failure would be silent and
 *       would look exactly like success.</li>
 * </ul>
 * Including the terminal values in the hash is what lets a row that changed AFTER a reconciliation
 * (an authorization later captured) be replayed again rather than being mistaken for the same fact.
 *
 * <h2>TIMED_OUT is the row this exists for, and the claim is narrower than it looks</h2>
 *
 * A provider row the simulator marks {@code TIMED_OUT} carries {@code capturedAmountMinor = 0}: the
 * provider decided and told nobody, and what it decided was to collect nothing. Replaying that as
 * {@code FAILED} turns ADR-015's guess into a confirmation from the provider's own record.
 * <p>
 * <b>That is a property of this provider's file, not of reconciliation in general.</b> A real
 * acquirer may report an outcome it genuinely does not yet know, and a file that means "unknown"
 * must never be read as "nothing moved". When a second provider arrives, that judgement belongs in
 * its adapter -- which is why {@link ProviderDayReport.Payment#status()} is carried as a raw string
 * and every status this class does not recognise is skipped rather than assumed.
 */
public final class ReconcileProviderDayService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileProviderDayService.class);

    /**
     * Marks an event id as this job's rather than the provider's. Without it a minted id could
     * collide with a real callback's and the replay would vanish into the dedup table.
     */
    private static final String RECONCILED_PREFIX = "recon:";

    private static final String TIMED_OUT_CODE = "provider_reported_no_collection";
    private static final String TIMED_OUT_MESSAGE =
        "The provider's reconciliation report shows nothing was collected for this payment.";

    private final ProviderReconciliationSource source;
    private final PaymentRepair payments;
    private final RefundRepair refunds;
    private final int lookbackDays;
    private final Clock clock;

    public ReconcileProviderDayService(
        ProviderReconciliationSource source,
        PaymentRepair payments,
        RefundRepair refunds,
        int lookbackDays,
        Clock clock
    ) {
        // A lookback of zero would reconcile only today, and today is the one day guaranteed to be
        // incomplete: a payment made twenty seconds ago has a callback still in flight, so its
        // divergence is not a divergence yet.
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("Reconciliation lookback must cover at least one day");
        }

        this.source = source;
        this.payments = payments;
        this.refunds = refunds;
        this.lookbackDays = lookbackDays;
        this.clock = clock;
    }

    /**
     * Reconciles the recent past, oldest day first.
     *
     * <h2>Why a window rather than yesterday</h2>
     *
     * A callback can be late by more than a day, the provider's file for a day can be amended, and
     * the job itself can be down. Reconciling one day means any of those three silently skips a day
     * forever. Re-running old days is safe by construction -- the deterministic event id makes a
     * repeat a duplicate -- so a window costs a little repeated work and removes a whole class of
     * permanent gap.
     * <p>
     * <b>Today is included</b> and is expected to produce transient UNRESOLVED and ALREADY_CONSISTENT
     * rows for payments still in flight. That is not an error; it is the reason the counts below
     * separate REPAIRED from everything else.
     */
    public ReconciliationResult reconcile() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        ReconciliationResult total = ReconciliationResult.empty();

        for (int daysAgo = lookbackDays - 1; daysAgo >= 0; daysAgo--) {
            total = total.plus(reconcile(today.minusDays(daysAgo)));
        }

        return total;
    }

    /**
     * One day.
     *
     * @throws ProviderReportUnavailableException when the provider could not be reached. Propagated
     *     rather than counted, and it is the one failure that is: a report that was never read has
     *     repaired nothing, and swallowing that would let an unreachable provider look like a clean
     *     reconciliation every night.
     */
    public ReconciliationResult reconcile(LocalDate date) {
        ProviderDayReport report = source.fetch(date);

        ReconciliationResult result = ReconciliationResult.empty();

        for (ProviderDayReport.Payment row : report.payments()) {
            result = result.plus(repairPayment(row));
        }

        for (ProviderDayReport.Refund row : report.refunds()) {
            result = result.plus(repairRefund(row));
        }

        if (result.repaired() > 0) {
            // WARN, not INFO. Every repair is a divergence that existed until this ran -- money
            // PayMesh had wrong. A platform where this is routinely non-zero has a delivery problem
            // that reconciliation is papering over, and that deserves to be visible.
            log.warn(
                "Reconciliation repaired divergences date={} repaired={} examined={}",
                date, result.repaired(), result.examined()
            );
        }

        if (result.unresolved() > 0 || result.errored() > 0) {
            log.warn(
                "Reconciliation could not act on some rows date={} unresolved={} errored={}",
                date, result.unresolved(), result.errored()
            );
        }

        return result;
    }

    /**
     * One payment row.
     * <p>
     * Every {@code catch} here is the same rule the outbox relay and both sweepers follow: a row that
     * throws is counted, never rethrown, so one unrepairable payment cannot disable reconciliation
     * for every other one in the file.
     */
    private ReconciliationResult repairPayment(ProviderDayReport.Payment row) {
        ReplayedOutcome outcome = outcomeOf(row.status());

        if (outcome == null) {
            // A non-terminal row (still authorizing, awaiting a customer step) or a status this
            // provider added that PayMesh has never heard of. Neither is a divergence: there is no
            // settled fact to replay. Skipped rather than counted as unresolved, which is reserved
            // for rows that ARE settled and cannot be matched.
            return ReconciliationResult.empty();
        }

        if (isBlank(row.callbackReference()) && isBlank(row.providerPaymentId())) {
            return ReconciliationResult.of(RepairOutcome.UNRESOLVED);
        }

        try {
            return ReconciliationResult.of(payments.replay(
                row.callbackReference(),
                row.providerPaymentId(),
                outcome,
                // Only what the outcome actually asserts. A FAILED row claiming an authorized
                // amount would be refused by the intent as a mismatch -- correctly -- and the
                // repair would be lost to a field that means nothing on that path.
                outcome == ReplayedOutcome.AUTHORIZED ? row.amountMinor() : null,
                outcome == ReplayedOutcome.SUCCEEDED ? row.capturedAmountMinor() : null,
                failureCodeFor(row, outcome),
                failureMessageFor(row, outcome),
                eventIdFor("pay", row.providerPaymentId(), outcome.name(), row.capturedAmountMinor()),
                row.updatedAt()
            ));
        } catch (RuntimeException failure) {
            log.warn(
                "Could not reconcile provider payment providerPaymentId={} status={}",
                row.providerPaymentId(), row.status(), failure
            );

            return ReconciliationResult.of(RepairOutcome.ERRORED);
        }
    }

    private ReconciliationResult repairRefund(ProviderDayReport.Refund row) {
        Boolean succeeded = refundSucceeded(row.status());

        if (succeeded == null) {
            return ReconciliationResult.empty();
        }

        // A refund is resolved ONLY by the reference PayMesh supplied. Unlike a payment there is no
        // fallback: Refund's callback route identifies a refund by its own id, and the provider's
        // refund id was never recorded against it. A row written before ADR-026 carries neither.
        if (isBlank(row.callbackReference())) {
            return ReconciliationResult.of(RepairOutcome.UNRESOLVED);
        }

        try {
            return ReconciliationResult.of(refunds.replay(
                row.callbackReference(),
                row.providerRefundId(),
                succeeded,
                succeeded ? null : row.failureCode(),
                succeeded ? null : row.failureMessage(),
                eventIdFor("ref", row.providerRefundId(), row.status(), row.amountMinor()),
                row.updatedAt()
            ));
        } catch (RuntimeException failure) {
            log.warn(
                "Could not reconcile provider refund providerRefundId={} status={}",
                row.providerRefundId(), row.status(), failure
            );

            return ReconciliationResult.of(RepairOutcome.ERRORED);
        }
    }

    /**
     * The provider's status vocabulary, mapped to the outcome PayMesh's callback route speaks.
     * <p>
     * {@code null} means "nothing settled to replay", and it covers two different things on purpose:
     * a status that is genuinely still in flight, and one this code has never seen. Both must lead to
     * doing nothing rather than to a default, because every value here moves money.
     */
    private static ReplayedOutcome outcomeOf(String providerStatus) {
        if (providerStatus == null) {
            return null;
        }

        return switch (providerStatus) {
            case "CAPTURED" -> ReplayedOutcome.SUCCEEDED;
            case "AUTHORIZED" -> ReplayedOutcome.AUTHORIZED;
            case "DECLINED" -> ReplayedOutcome.FAILED;
            case "REQUIRES_ACTION" -> ReplayedOutcome.REQUIRES_ACTION;
            // The provider decided and told nobody, and what it decided was to collect nothing --
            // capturedAmountMinor is 0 on these rows. This is the bucket ADR-015's sweeper guesses
            // at; here the guess is replaced by the provider's own record. See the class javadoc for
            // why this reading is specific to this provider's file.
            case "TIMED_OUT" -> ReplayedOutcome.FAILED;
            default -> null;
        };
    }

    /** Two states only: a provider either sent the money back or refused to. */
    private static Boolean refundSucceeded(String providerStatus) {
        if (providerStatus == null) {
            return null;
        }

        return switch (providerStatus) {
            case "SUCCEEDED" -> Boolean.TRUE;
            case "FAILED" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * A TIMED_OUT row carries no failure code -- the provider never articulated one, because it never
     * spoke. Supplying one of PayMesh's own is more honest than an empty column: it says plainly
     * WHICH mechanism concluded the payment failed, which is the first thing anyone investigating
     * this row will want to know.
     */
    private static String failureCodeFor(ProviderDayReport.Payment row, ReplayedOutcome outcome) {
        if (outcome != ReplayedOutcome.FAILED) {
            return null;
        }

        return isBlank(row.failureCode()) ? TIMED_OUT_CODE : row.failureCode();
    }

    private static String failureMessageFor(ProviderDayReport.Payment row, ReplayedOutcome outcome) {
        if (outcome != ReplayedOutcome.FAILED) {
            return null;
        }

        return isBlank(row.failureMessage()) ? TIMED_OUT_MESSAGE : row.failureMessage();
    }

    /**
     * A stable id for "this row, in this terminal state", prefixed so it can never be mistaken for
     * the provider's own.
     * <p>
     * Hashed rather than concatenated for one reason that is not aesthetics: {@code ProviderEvent}
     * caps an event id at 120 characters, and a raw concatenation of provider ids is unbounded. A
     * truncated key would collide silently, and a collision here means a repair swallowed as a
     * duplicate.
     */
    private static String eventIdFor(String kind, String id, String state, long amountMinor) {
        String key = kind + '|' + id + '|' + state + '|' + amountMinor;

        return RECONCILED_PREFIX + sha256(key);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256. Wrapped rather than declared so callers are not made to
            // handle a condition that cannot occur.
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * What a run did.
     *
     * @param examined rows that carried a settled outcome and were acted on. Rows still in flight are
     *                 not counted -- they were not examined so much as passed over
     * @param repaired divergences corrected. <b>The number that matters</b>, and the one worth an
     *                 alert when it is persistently non-zero: it counts facts PayMesh had wrong
     * @param unresolved settled provider rows naming nothing PayMesh recognises
     * @param errored  rows whose replay threw. Counted, never rethrown
     */
    public record ReconciliationResult(int examined, int repaired, int unresolved, int errored) {

        static ReconciliationResult empty() {
            return new ReconciliationResult(0, 0, 0, 0);
        }

        static ReconciliationResult of(RepairOutcome outcome) {
            return new ReconciliationResult(
                1,
                outcome == RepairOutcome.REPAIRED ? 1 : 0,
                outcome == RepairOutcome.UNRESOLVED ? 1 : 0,
                outcome == RepairOutcome.ERRORED ? 1 : 0
            );
        }

        ReconciliationResult plus(ReconciliationResult other) {
            return new ReconciliationResult(
                examined + other.examined,
                repaired + other.repaired,
                unresolved + other.unresolved,
                errored + other.errored
            );
        }
    }
}
