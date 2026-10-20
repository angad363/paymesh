package com.paymesh.settlement;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.ledger.application.PostRefundReversalService;
import com.paymesh.ledger.application.PostSettlementJournalsService;
import com.paymesh.ledger.application.ReleaseAvailableFundsService;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.settlement.application.CutSettlementBatchesService;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.settlement.application.PayoutRepository;
import com.paymesh.settlement.application.RecordPayoutCallbackService;
import com.paymesh.settlement.application.SettlementBatchRepository;
import com.paymesh.settlement.domain.PayoutStatus;
import com.paymesh.settlement.domain.SettlementBatchStatus;
import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.PayoutOutcome;
import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settlement money loop against real PostgreSQL: cut moves available into in-transit, and the
 * provider's word -- paid or returned -- is what discharges it. SDD 17.1/17.2, ADR-032.
 *
 * <p>Drives the services and the outbox relay directly rather than the HTTP layer: the point here
 * is the ledger arithmetic, and {@code SimulatorCallbackDeliveryIntegrationTest} already proves the
 * callback crosses a socket and is signature-checked. Each journal is posted by the Ledger from a
 * committed outbox event, so every assertion below follows a {@link #drain()} that publishes it.
 *
 * <p>Deliberately NOT {@code @Transactional}: the loop commits a batch, a payout and events across
 * several transactions and its idempotency turns on what actually committed, exactly as the release
 * job's test explains. Every test registers its own merchant and scopes to it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SettlementLoopIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:00:00Z");
    private static final long CAPTURED = 10_000L;
    private static final String DESTINATION = "acct_merchant_test";

    @Autowired
    private PostPaymentCapturedService postCapture;

    @Autowired
    private ReleaseAvailableFundsService release;

    @Autowired
    private GetSettlementConfigService settlementConfigs;

    @Autowired
    private CutSettlementBatchesService cut;

    @Autowired
    private RecordPayoutCallbackService payoutCallbacks;

    @Autowired
    private PostRefundReversalService postRefund;

    @Autowired
    private SettlementBatchRepository batches;

    @Autowired
    private PayoutRepository payouts;

    @Autowired
    private PostSettlementJournalsService settlementJournals;

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Cut moves available into in-transit, and a PAID callback discharges it against PayMesh cash. */
    @Test
    void aPaidPayoutMovesAvailableThroughInTransitAndOut() {
        MerchantId merchantId = payableMerchant();
        captureAndRelease(merchantId, CAPTURED);

        Payout payout = cutOneBatch(merchantId);

        assertThat(balanceOf(merchantId))
            .as("cut took the money out of available and parked it in transit")
            .isEqualTo(new MerchantBalance("INR", 0L, 0L, CAPTURED));

        record(payout, PayoutOutcome.SUCCEEDED);

        assertThat(balanceOf(merchantId).inSettlementMinor())
            .as("the provider paid, so in-transit is discharged and nothing is left settleable")
            .isZero();
        assertThat(reload(payout).isTerminal())
            .as("a paid payout is terminal")
            .isTrue();
    }

    /** A FAILED callback returns the money to available, by a new journal, ready to be cut again. */
    @Test
    void aFailedPayoutReturnsTheFundsToAvailable() {
        MerchantId merchantId = payableMerchant();
        captureAndRelease(merchantId, CAPTURED);

        Payout payout = cutOneBatch(merchantId);
        record(payout, PayoutOutcome.FAILED);

        assertThat(balanceOf(merchantId))
            .as("the payout failed terminally, so the funds come back to available")
            .isEqualTo(new MerchantBalance("INR", 0L, CAPTURED, 0L));
        assertThat(reload(payout).isTerminal())
            .as("a failed payout is terminal")
            .isTrue();
    }

    /**
     * ADR-047 section 4, option A. A refund lands between the cut's read and the Ledger consuming
     * {@code settlement.batch_cut}: the batch no longer fits, so the Ledger posts NOTHING, emits
     * {@code settlement.batch_rejected}, and Settlement reverts its own side -- batch RETURNED,
     * payout FAILED, no {@code payout.returned} (there is no in-transit to return).
     */
    @Test
    void aStaleBatchIsRejectedByTheLedgerAndRevertedBySettlement() {
        MerchantId merchantId = payableMerchant();
        String paymentIntentId = "pi_" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status ->
            postCapture.post(merchantId, paymentIntentId, CAPTURED, "INR", CREATED_AT)
        );
        release.release();

        // Cut from the read, but do NOT relay yet: the Ledger has not consumed the cut.
        List<SettlementBatch> cutBatches = cut.cutFor(merchantId);
        assertThat(cutBatches).hasSize(1);
        SettlementBatch batch = cutBatches.get(0);

        // The race: a refund shrinks available after the read and before the consume.
        transactionTemplate.executeWithoutResult(status -> postRefund.post(
            merchantId, "rfd_" + UUID.randomUUID(), paymentIntentId, 4_000L, "INR", CREATED_AT
        ));
        drain();

        assertThat(balanceOf(merchantId))
            .as("the stale cut posted nothing: available is what the refund left, in-transit is empty")
            .isEqualTo(new MerchantBalance("INR", 0L, CAPTURED - 4_000L, 0L));
        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .as("Settlement reverted its own side of the rejected cut")
            .isEqualTo(SettlementBatchStatus.RETURNED);
        assertThat(payouts.findByBatch(batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(PayoutStatus.FAILED);

        // The reverted batch's items are free again: the next cut re-itemises from a fresh read.
        List<SettlementBatch> recut = cut.cutFor(merchantId);
        assertThat(recut).hasSize(1);
        assertThat(recut.get(0).netAmountMinor()).isEqualTo(CAPTURED - 4_000L);
    }

    /**
     * ADR-047 section 8, in-process path. A payout is due the instant the batch is cut, but it is
     * submittable only once the Ledger has posted the cut ({@code ledger.batch_posted} marks the
     * batch POSTED). SubmitPayoutsService reads exactly {@code payouts.findDue}.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop the {@code POSTED} join from
     * {@code SpringDataPayoutRepository.findDue}, or stop the ledger emitting {@code ledger.batch_posted}.
     */
    @Test
    void aPayoutIsSubmittableOnlyAfterTheLedgerHasPostedTheCut() {
        MerchantId merchantId = payableMerchant();
        captureAndRelease(merchantId, CAPTURED);
        Instant later = Instant.now().plus(Duration.ofHours(1));

        SettlementBatch batch = cut.cutFor(merchantId).get(0);
        String payoutId = payouts.findByBatch(batch.settlementBatchId()).orElseThrow().payoutId().value();

        assertThat(payouts.findDue(later, 1_000))
            .as("cut, ledger has not posted: the provider must not be paid yet")
            .doesNotContain(payoutId);

        drain();

        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(SettlementBatchStatus.POSTED);
        assertThat(payouts.findDue(later, 1_000)).as("posted: now submittable").contains(payoutId);
    }

    /**
     * V41's backfill marks POSTED only a PENDING_PAYOUT batch whose cut journal the Ledger already
     * posted; a batch whose batch_cut is still in flight stays PENDING_PAYOUT. Runs the migration's
     * own UPDATE (read from the classpath) against the test database.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop the EXISTS clause from V41 (the old
     * "all PENDING_PAYOUT" backfill) -- the in-flight batch is then POSTED.
     */
    @Test
    void theBackfillPostsOnlyBatchesTheLedgerHasAlreadyJournalled() throws Exception {
        MerchantId journalled = payableMerchant();
        captureAndRelease(journalled, CAPTURED);
        SettlementBatch posted = cut.cutFor(journalled).get(0);
        drain();
        jdbc.update("update settlement.settlement_batches set status = 'PENDING_PAYOUT' where settlement_batch_id = ?",
            posted.settlementBatchId().value());

        MerchantId inFlight = payableMerchant();
        captureAndRelease(inFlight, CAPTURED);
        SettlementBatch pending = cut.cutFor(inFlight).get(0); // batch_cut NOT relayed: no journal

        String migration = new String(
            new org.springframework.core.io.ClassPathResource("db/migration/V41__batch_posted_status.sql")
                .getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8
        );
        jdbc.update(migration.substring(migration.indexOf("UPDATE settlement.settlement_batches")));

        assertThat(batches.find(journalled, posted.settlementBatchId()).orElseThrow().status())
            .as("the Ledger journalled this cut before the upgrade").isEqualTo(SettlementBatchStatus.POSTED);
        assertThat(batches.find(inFlight, pending.settlementBatchId()).orElseThrow().status())
            .as("no journal yet: must wait for ledger.batch_posted, not bypass the gate")
            .isEqualTo(SettlementBatchStatus.PENDING_PAYOUT);
    }

    /**
     * ITEM 2. A refund that has debited available but not yet committed must make a concurrent
     * batch-cut check WAIT (row lock on the available account), then see the refund and reject --
     * never pass the check on the pre-refund balance and drive available negative.
     * <p>
     * A genuine two-thread test: B must still be blocked while A holds its uncommitted refund.
     * <b>Sabotage that must turn this red:</b> remove {@code openAndLock} from
     * {@code PostSettlementJournalsService.postBatchCut} -- B then finishes inside the wait window
     * and posts. (Removing it from the refund path alone does NOT: the refund's entry insert already
     * takes a FOR KEY SHARE on the account row via the entry's foreign key, which conflicts with B's
     * FOR UPDATE. The refund-side lock is explicit belt-and-braces, not what this test pins.)
     */
    @Test
    void aBatchCheckWaitsForAnInFlightRefundInsteadOfDrivingAvailableNegative() throws Exception {
        MerchantId merchantId = payableMerchant();
        String paymentIntentId = "pi_" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status ->
            postCapture.post(merchantId, paymentIntentId, CAPTURED, "INR", CREATED_AT)
        );
        release.release();

        CountDownLatch refundPosted = new CountDownLatch(1);
        CountDownLatch commitRefund = new CountDownLatch(1);

        CompletableFuture<Void> refund = CompletableFuture.runAsync(() ->
            transactionTemplate.executeWithoutResult(status -> {
                postRefund.post(merchantId, "rfd_" + UUID.randomUUID(), paymentIntentId, 4_000L, "INR", CREATED_AT);
                refundPosted.countDown();
                await(commitRefund);
            })
        );
        assertThat(refundPosted.await(10, TimeUnit.SECONDS)).isTrue();

        String batchId = "stl_" + UUID.randomUUID();
        CompletableFuture<Boolean> post = CompletableFuture.supplyAsync(() ->
            transactionTemplate.execute(status -> settlementJournals
                .postBatchCut(merchantId, batchId, CAPTURED, "INR", CREATED_AT).isPresent())
        );

        assertThat(post.isDone() || waitsFor(post, 750))
            .as("the check must still be blocked on the refund's lock, not racing past it")
            .isFalse();

        commitRefund.countDown();
        refund.get(10, TimeUnit.SECONDS);

        assertThat(post.get(10, TimeUnit.SECONDS))
            .as("after the refund committed the batch no longer fits: rejected, nothing posted")
            .isFalse();
        assertThat(balanceOf(merchantId))
            .isEqualTo(new MerchantBalance("INR", 0L, CAPTURED - 4_000L, 0L));
    }

    private static boolean waitsFor(CompletableFuture<?> future, long millis) {
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException blocked) {
            return false;
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Payout cutOneBatch(MerchantId merchantId) {
        List<SettlementBatch> cutBatches = cut.cutFor(merchantId);
        assertThat(cutBatches).as("exactly one batch is cut").hasSize(1);
        drain();
        return payouts.findByBatch(cutBatches.get(0).settlementBatchId()).orElseThrow();
    }

    private void record(Payout payout, PayoutOutcome outcome) {
        payoutCallbacks.record(
            "SIMULATOR",
            "whv_" + UUID.randomUUID(),
            payout.payoutId().value(),
            outcome,
            outcome == PayoutOutcome.FAILED ? "insufficient funds at the bank" : null,
            CREATED_AT,
            "hash-" + UUID.randomUUID()
        );
        drain();
    }

    private void captureAndRelease(MerchantId merchantId, long amountMinor) {
        transactionTemplate.executeWithoutResult(status ->
            postCapture.post(merchantId, "pi_" + UUID.randomUUID(), amountMinor, "INR", CREATED_AT)
        );
        release.release();
    }

    private Payout reload(Payout payout) {
        return payouts.find(payout.payoutId()).orElseThrow();
    }

    private MerchantBalance balanceOf(MerchantId merchantId) {
        return balances.byMerchant(merchantId).stream()
            .filter(balance -> balance.currency().equals("INR"))
            .findFirst()
            .orElseThrow();
    }

    private MerchantId payableMerchant() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();

        // Zero holding period so release clears immediately; a destination and a minimum of one so
        // the merchant is payable and any positive net qualifies.
        settlementConfigs.set(merchantId, Duration.ZERO, DESTINATION, 1L);
        return merchantId;
    }

    /** Publish outbox events until nothing more moves, so the Ledger has posted every journal. */
    private void drain() {
        while (relay.publish().published() > 0) {
            // keep going
        }
    }
}
