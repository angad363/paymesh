package com.paymesh.settlement;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.ledger.application.ReleaseAvailableFundsService;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.settlement.application.CutSettlementBatchesService;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.settlement.application.PayoutRepository;
import com.paymesh.settlement.application.RecordPayoutCallbackService;
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
    private PayoutRepository payouts;

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
