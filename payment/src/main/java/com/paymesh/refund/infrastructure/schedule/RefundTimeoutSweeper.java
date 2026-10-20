package com.paymesh.refund.infrastructure.schedule;

import com.paymesh.refund.application.TimeOutProcessingRefundsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer. Same shape as {@code ProcessingTimeoutSweeper}, and absent for the same reason it was
 * needed: Payment had one and Refund did not.
 * <p>
 * The bean is {@code @ConditionalOnProperty} in the configuration, so switching it off removes it
 * rather than leaving it running and doing nothing -- which matters under the {@code dev} profile
 * the whole test suite uses, where a timer failing refunds mid-assertion is a flake generator.
 */
public final class RefundTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(RefundTimeoutSweeper.class);

    private final TimeOutProcessingRefundsService timeOutProcessingRefunds;

    public RefundTimeoutSweeper(TimeOutProcessingRefundsService timeOutProcessingRefunds) {
        this.timeOutProcessingRefunds = timeOutProcessingRefunds;
    }

    @Scheduled(
        fixedDelayString = "${paymesh.refunds.processing-timeout.interval}",
        initialDelayString = "${paymesh.refunds.processing-timeout.interval}"
    )
    public void sweep() {
        TimeOutProcessingRefundsService.SweepResult result = timeOutProcessingRefunds.sweep();

        // WARN rather than INFO when anything was timed out: each one is money a merchant thought
        // was going back and that PayMesh has now given up on, which is not routine traffic.
        if (result.failed() > 0 || result.errored() > 0) {
            log.warn(
                "Refund timeout sweep examined={} failed={} errored={}",
                result.examined(), result.failed(), result.errored()
            );
        }
    }
}
