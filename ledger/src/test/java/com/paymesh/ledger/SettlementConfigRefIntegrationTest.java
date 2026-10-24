package com.paymesh.ledger;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.HoldingPeriodPolicy;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.ledger.application.ReleaseAvailableFundsService;
import com.paymesh.ledger.infrastructure.config.LedgerReleaseProperties;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static com.paymesh.ledger.LedgerTestEvents.AT;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR-051: the ledger learns each merchant's holding period from settlement.config.updated. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SettlementConfigRefIntegrationTest {

    @Autowired private EventDispatcher dispatcher;
    @Autowired private HoldingPeriodPolicy policy;
    @Autowired private ReleaseAvailableFundsService release;
    @Autowired private GetBalancesService balances;
    @Autowired private LedgerReleaseProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void projectsThePeriodIdempotentlyAndLastWriteWins() {
        MerchantId merchantId = MerchantId.generate();
        var first = LedgerTestEvents.settlementConfigUpdated(EventId.generate(), merchantId, 3600, AT);

        dispatcher.dispatch(first);
        dispatcher.dispatch(first);
        dispatcher.dispatch(LedgerTestEvents.settlementConfigUpdated(EventId.generate(), merchantId, 3600, AT));
        assertThat(policy.forMerchant(merchantId)).isEqualTo(Duration.ofHours(1));
        assertThat(rows(merchantId)).isOne();

        dispatcher.dispatch(LedgerTestEvents.settlementConfigUpdated(
            EventId.generate(), merchantId, 60, AT.plusSeconds(10)));
        assertThat(policy.forMerchant(merchantId)).isEqualTo(Duration.ofMinutes(1));

        dispatcher.dispatch(LedgerTestEvents.settlementConfigUpdated(
            EventId.generate(), merchantId, 999_999, AT.minusSeconds(10)));
        assertThat(policy.forMerchant(merchantId)).as("an older event never wins").isEqualTo(Duration.ofMinutes(1));
        assertThat(rows(merchantId)).isOne();
    }

    @Test
    void anUnconfiguredMerchantGetsTheDefaultAsAValueAndNoRowIsWritten() {
        MerchantId merchantId = MerchantId.generate();

        assertThat(policy.forMerchant(merchantId)).isEqualTo(properties.defaultHoldingPeriod());
        assertThat(rows(merchantId)).isZero();
    }

    /** Pinned to settlement's platform default (paymesh.settlement.default-holding-period: 7d). */
    @Test
    void theFallbackEqualsSettlementsPlatformDefault() {
        assertThat(properties.defaultHoldingPeriod()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void aReleaseRunUsesTheProjectedPeriodNotTheFlatDefault() {
        MerchantId longHold = MerchantId.generate();
        MerchantId noHold = MerchantId.generate();
        MerchantId unconfigured = MerchantId.generate();
        // Captured at AT (2026-08-02): long past the 7d default, inside 3650d.
        dispatcher.dispatch(LedgerTestEvents.settlementConfigUpdated(
            EventId.generate(), longHold, Duration.ofDays(3650).toSeconds(), Instant.now()));
        dispatcher.dispatch(LedgerTestEvents.settlementConfigUpdated(
            EventId.generate(), noHold, 0, Instant.now()));
        for (MerchantId m : new MerchantId[] {longHold, noHold, unconfigured}) {
            dispatcher.dispatch(LedgerTestEvents.paymentSucceeded(m, LedgerTestEvents.newPaymentIntentId(), 4000));
        }

        release.release();

        assertThat(balances.forMerchant(longHold)).containsExactly(new MerchantBalance("INR", 4000L, 0L));
        assertThat(balances.forMerchant(noHold)).containsExactly(new MerchantBalance("INR", 0L, 4000L));
        assertThat(balances.forMerchant(unconfigured)).containsExactly(new MerchantBalance("INR", 0L, 4000L));
    }

    private Long rows(MerchantId merchantId) {
        return jdbc.queryForObject(
            "select count(*) from settlement_config_ref where merchant_id = ?", Long.class, merchantId.value());
    }
}
