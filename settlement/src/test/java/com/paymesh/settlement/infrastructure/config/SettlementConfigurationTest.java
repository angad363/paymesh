package com.paymesh.settlement.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.settlement.application.AvailableFunds;
import com.paymesh.settlement.infrastructure.ledger.LedgerHttpAvailableFunds;
import com.paymesh.shared.outbox.application.EventHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SettlementConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AvailableFunds availableFunds;

    @Autowired
    private List<EventHandler> handlers;

    /** Fails if {@code @EnableScheduling} leaves SettlementConfiguration: no sweep, no relay, ever. */
    @Test
    void schedulingIsEnabledSoTheSweeperAndRelayRun() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
    }

    @Test
    void theLedgerIsReadOverHttpAndTheRevertIsSubscribed() {
        assertThat(availableFunds).isInstanceOf(LedgerHttpAvailableFunds.class);
        assertThat(handlers).extracting(EventHandler::eventType).contains("settlement.batch_rejected", "ledger.batch_posted");
    }
}
