package com.paymesh.ledger;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.ledger.infrastructure.schedule.AvailableFundsReleaseSweeper;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The sweeper exists only with {@code paymesh.ledger.release.enabled=true} (ADR-050 section 5). */
@SpringBootTest(properties = "paymesh.ledger.release.enabled=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AvailableFundsReleaseFlagOnTest {

    @Autowired
    private AvailableFundsReleaseSweeper sweeper;

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private GetBalancesService balances;

    @Test
    void theSweeperMovesClearedFundsFromPendingToAvailableExactlyOnce() {
        MerchantId merchantId = MerchantId.generate();
        // Captured 2026-08-02, well past the 7d default holding period.
        dispatcher.dispatch(LedgerTestEvents.paymentSucceeded(merchantId, LedgerTestEvents.newPaymentIntentId(), 4000));
        assertThat(balances.forMerchant(merchantId))
            .containsExactly(new MerchantBalance("INR", 4000L, 0L));

        sweeper.release();
        sweeper.release();

        assertThat(balances.forMerchant(merchantId))
            .as("pending drained, available credited once however often the job runs")
            .containsExactly(new MerchantBalance("INR", 0L, 4000L));
    }
}
