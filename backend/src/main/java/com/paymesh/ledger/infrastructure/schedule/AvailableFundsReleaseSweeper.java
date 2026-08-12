package com.paymesh.ledger.infrastructure.schedule;

import com.paymesh.ledger.application.ReleaseAvailableFundsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer. NO LOGIC (CLAUDE.md's rule for every scheduled bean here): one call and one log line,
 * so every rule lives in a plain object taking an injected {@code Clock} that a test drives
 * directly.
 */
@Component
@ConditionalOnProperty(value = "paymesh.ledger.release.enabled", havingValue = "true")
public class AvailableFundsReleaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(AvailableFundsReleaseSweeper.class);

    private final ReleaseAvailableFundsService releaseAvailableFunds;

    public AvailableFundsReleaseSweeper(ReleaseAvailableFundsService releaseAvailableFunds) {
        this.releaseAvailableFunds = releaseAvailableFunds;
    }

    @Scheduled(
        fixedDelayString = "${paymesh.ledger.release.interval}",
        initialDelayString = "${paymesh.ledger.release.initial-delay}"
    )
    public void release() {
        ReleaseAvailableFundsService.ReleaseResult result = releaseAvailableFunds.release();

        if (result.examined() > 0) {
            log.info(
                "Available funds release examined={} released={} held={} errored={}",
                result.examined(), result.released(), result.held(), result.errored()
            );
        }
    }
}
