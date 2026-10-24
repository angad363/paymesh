package com.paymesh.settlement;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.settlement.application.SettlementConfigRepository;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-051: every config write emits {@code settlement.config.updated} in the SAME transaction, on
 * both {@code set} overloads, so the ledger service's projection can never miss a change.
 * Deliberately NOT {@code @Transactional}: the point is what commits and what rolls back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SettlementConfigEventIntegrationTest {

    @Autowired private GetSettlementConfigService configs;
    @Autowired private SettlementConfigRepository repository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.paymesh.settlement.infrastructure.config.SettlementProperties properties;

    /** The ledger service's fallback (paymesh.ledger.release.default-holding-period) is pinned to this too. */
    @Test
    void platformDefaultIsSevenDays() {
        assertThat(properties.defaultHoldingPeriod()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void bothSetOverloadsEmitTheEventWithTheHoldingPeriod() {
        MerchantId merchantId = MerchantId.generate();

        configs.set(merchantId, Duration.ofHours(1));
        configs.set(merchantId, Duration.ofDays(2), "acct_x", 500L);

        var payloads = jdbc.queryForList(
            "select payload ->> 'holdingPeriodSeconds' from outbox_events "
                + "where event_type = 'settlement.config.updated' and aggregate_type = 'SETTLEMENT_CONFIG' "
                + "and aggregate_id = ? and merchant_id = ? and event_version = 1 order by occurred_at, event_id",
            String.class, merchantId.value(), merchantId.value());

        assertThat(payloads).containsExactlyInAnyOrder("3600", "172800");
    }

    @Test
    void aFailedAppendRollsTheConfigChangeBackToo() {
        MerchantId merchantId = MerchantId.generate();
        OutboxWriter failing = event -> { throw new IllegalStateException("append failed"); };
        var service = new GetSettlementConfigService(
            repository, Duration.ofDays(7), failing, transactions, Clock.systemUTC());

        assertThatThrownBy(() -> service.set(merchantId, Duration.ofHours(1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.set(merchantId, Duration.ofHours(1), "acct_x", 500L))
            .isInstanceOf(IllegalStateException.class);

        assertThat(repository.find(merchantId)).as("no config without its event").isEmpty();
    }
}
