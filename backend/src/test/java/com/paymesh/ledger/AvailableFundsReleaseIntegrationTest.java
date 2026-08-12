package com.paymesh.ledger;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.ledger.application.PostRefundReversalService;
import com.paymesh.ledger.application.ReleaseAvailableFundsService;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release job against real PostgreSQL: pending becomes available, once, in the right amount.
 * <p>
 * Deliberately NOT {@code @Transactional} -- the job posts one transaction per payment and its
 * idempotency turns on what previous runs actually committed, so an outer test transaction would
 * make the double-run test pass whether or not the code was right. Every test registers its own
 * merchant and scopes its assertions to it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AvailableFundsReleaseIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:00:00Z");
    private static final long CAPTURED = 10_000L;

    @Autowired
    private PostPaymentCapturedService postCapture;

    @Autowired
    private PostRefundReversalService postReversal;

    @Autowired
    private ReleaseAvailableFundsService release;

    @Autowired
    private GetSettlementConfigService settlementConfigs;

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The posting services normally run inside the event dispatcher's transaction and refuse to run
     * without one. Driving them directly means supplying it here.
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void movesClearedFundsFromPendingToAvailable() {
        MerchantId merchantId = existingMerchant();
        settlementConfigs.set(merchantId, Duration.ZERO);

        String paymentIntentId = capture(merchantId, CAPTURED);

        release.release();

        assertThat(balanceOf(merchantId))
            .as("the same money, in the other bucket")
            .isEqualTo(new MerchantBalance("INR", 0L, CAPTURED));
        assertThat(releaseCountFor(paymentIntentId)).isOne();
    }

    /** Inside the holding period nothing moves, and nothing is recorded as having tried. */
    @Test
    void leavesFundsPendingUntilTheHoldingPeriodHasPassed() {
        MerchantId merchantId = existingMerchant();
        settlementConfigs.set(merchantId, Duration.ofDays(3650));

        String paymentIntentId = capture(merchantId, CAPTURED);

        release.release();

        assertThat(balanceOf(merchantId)).isEqualTo(new MerchantBalance("INR", CAPTURED, 0L));
        assertThat(releaseCountFor(paymentIntentId)).isZero();
    }

    /**
     * TWO RUNS, ONE RELEASE. Guaranteed twice over and both are worth having: the candidate query
     * stops returning a released capture, and {@code uq_ledger_transactions_idempotency} would
     * refuse a second row even if it did.
     */
    @Test
    void releasesOnceHoweverOftenTheJobRuns() {
        MerchantId merchantId = existingMerchant();
        settlementConfigs.set(merchantId, Duration.ZERO);

        String paymentIntentId = capture(merchantId, CAPTURED);

        release.release();
        release.release();
        release.release();

        assertThat(releaseCountFor(paymentIntentId)).isOne();
        assertThat(balanceOf(merchantId)).isEqualTo(new MerchantBalance("INR", 0L, CAPTURED));
    }

    /**
     * THE TEST THE WHOLE DESIGN EXISTS FOR (ADR-031).
     *
     * <p>A refund before the funds clear must reduce what gets released. Releasing the gross would
     * leave the TOTAL correct and the SPLIT wrong -- pending at -3000 and available at 10000 -- and
     * available is the figure Settlement pays against, so the merchant would be paid 10000 for a
     * payment worth 7000 to them.
     *
     * <p>It works because the refund reversal references the PAYMENT rather than the refund, so the
     * per-payment sum already has the refund subtracted. That reference is the one thing this PR
     * changed in an existing journal, and this is why.
     */
    @Test
    void releasesOnlyWhatIsLeftAfterARefundThatLandedFirst() {
        MerchantId merchantId = existingMerchant();
        settlementConfigs.set(merchantId, Duration.ZERO);

        String paymentIntentId = capture(merchantId, CAPTURED);

        refund(merchantId, paymentIntentId, 3_000L);

        release.release();

        assertThat(balanceOf(merchantId))
            .as("7000 was left pending, so 7000 is what became available")
            .isEqualTo(new MerchantBalance("INR", 0L, 7_000L));
    }

    /** Refunded in full before clearing: nothing to release, and no empty journal recording that. */
    @Test
    void releasesNothingWhenThePaymentWasFullyRefundedFirst() {
        MerchantId merchantId = existingMerchant();
        settlementConfigs.set(merchantId, Duration.ZERO);

        String paymentIntentId = capture(merchantId, CAPTURED);

        refund(merchantId, paymentIntentId, CAPTURED);

        release.release();

        assertThat(releaseCountFor(paymentIntentId))
            .as("a zero-amount journal would be a row saying nothing happened")
            .isZero();
        assertThat(balanceOf(merchantId)).isEqualTo(new MerchantBalance("INR", 0L, 0L));
    }

    /**
     * The default applies to a merchant who never configured one, without writing them a row.
     * <p>
     * Captured at the REAL now rather than this class's backdated constant, because the default is
     * seven days and the constant is further in the past than that -- the first version of this
     * test asserted the funds were held and they had legitimately cleared. The other tests can
     * backdate freely; this one is about the default's length, so it has to respect it.
     */
    @Test
    void appliesThePlatformDefaultToAMerchantWithNoConfig() {
        MerchantId merchantId = existingMerchant();

        capture(merchantId, CAPTURED, Instant.now());

        release.release();

        assertThat(balanceOf(merchantId))
            .as("the default holding period is days, so nothing clears immediately")
            .isEqualTo(new MerchantBalance("INR", CAPTURED, 0L));
        assertThat(jdbc.queryForObject(
            "select count(*) from settlement_configs where merchant_id = ?",
            Long.class, merchantId.value()
        )).as("reading a default must not persist one").isZero();
    }

    /**
     * A REFUND AFTER RELEASE COMES OUT OF AVAILABLE, NOT PENDING.
     *
     * <p>Once released, nothing of this payment is pending. Debiting pending would drive it
     * negative while leaving available still claiming money the merchant no longer has -- and
     * available is the figure Settlement pays against, so that error is one that gets paid out.
     */
    @Test
    void refundsAgainstAlreadyReleasedFundsDebitAvailable() {
        MerchantId merchantId = existingMerchant();
        settlementConfigs.set(merchantId, Duration.ZERO);

        String paymentIntentId = capture(merchantId, CAPTURED);

        release.release();

        refund(merchantId, paymentIntentId, 4_000L);

        assertThat(balanceOf(merchantId))
            .as("pending is untouched; the refund came out of the released side")
            .isEqualTo(new MerchantBalance("INR", 0L, 6_000L));
    }

    /**
     * And it may go negative. A merchant refunding more than is left available owes PayMesh the
     * difference, which is a real state rather than a number to clamp at zero.
     */
    @Test
    void letsAvailableGoNegativeWhenARefundExceedsIt() {
        MerchantId merchantId = existingMerchant();
        settlementConfigs.set(merchantId, Duration.ZERO);

        String paymentIntentId = capture(merchantId, CAPTURED);

        release.release();

        refund(merchantId, paymentIntentId, CAPTURED);
        refund(merchantId, paymentIntentId, 2_500L);

        assertThat(balanceOf(merchantId).availableMinor())
            .as("PayMesh is owed 2500 by this merchant, and the ledger says so")
            .isEqualTo(-2_500L);
    }

    private String capture(MerchantId merchantId, long amountMinor) {
        return capture(merchantId, amountMinor, CREATED_AT);
    }

    private String capture(MerchantId merchantId, long amountMinor, Instant occurredAt) {
        String paymentIntentId = "pi_" + UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status ->
            postCapture.post(merchantId, paymentIntentId, amountMinor, "INR", occurredAt)
        );

        return paymentIntentId;
    }

    private void refund(MerchantId merchantId, String paymentIntentId, long amountMinor) {
        transactionTemplate.executeWithoutResult(status -> postReversal.post(
            merchantId, "ref_" + UUID.randomUUID(), paymentIntentId, amountMinor, "INR", CREATED_AT
        ));
    }

    private MerchantBalance balanceOf(MerchantId merchantId) {
        return balances.byMerchant(merchantId).stream()
            .filter(balance -> balance.currency().equals("INR"))
            .findFirst()
            .orElseThrow();
    }

    private Long releaseCountFor(String paymentIntentId) {
        return jdbc.queryForObject(
            "select count(*) from ledger_transactions where idempotency_key = ?",
            Long.class, "funds-released:" + paymentIntentId
        );
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();
    }
}
