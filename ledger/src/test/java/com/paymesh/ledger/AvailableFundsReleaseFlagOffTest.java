package com.paymesh.ledger;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.infrastructure.schedule.AvailableFundsReleaseSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXCLUSIVE OWNERSHIP OF THE RELEASE JOB (ADR-050 section 5): the flag defaults OFF, so a stock start
 * cannot run the sweeper beside the monolith's. Two releases would credit available twice.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AvailableFundsReleaseFlagOffTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theSweeperIsNotRegistered() {
        assertThat(context.getBeansOfType(AvailableFundsReleaseSweeper.class)).isEmpty();
    }
}
