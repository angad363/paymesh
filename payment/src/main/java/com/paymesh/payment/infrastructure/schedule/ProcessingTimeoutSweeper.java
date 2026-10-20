package com.paymesh.payment.infrastructure.schedule;

import com.paymesh.payment.application.TimeOutProcessingPaymentsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else.
 * <p>
 * A scheduled job is a framework component and may carry framework annotations, but that is exactly
 * why no logic may live here: anything inside a scheduled method can only be exercised by starting a
 * context and waiting. Every rule the timeout applies -- the age, the lock, the re-check, what is
 * written and what is deliberately left alone -- lives in {@link TimeOutProcessingPaymentsService},
 * an ordinary object taking an injected {@code Clock}. If a condition, a loop or a decision ever
 * appears in this file, it is in the wrong file.
 * <p>
 * {@code fixedDelay} rather than {@code fixedRate}, for the reason {@code OrderExpirySweeper} gives:
 * this is catch-up work, and overlapping sweeps contending on the same intent rows buy nothing.
 */
public final class ProcessingTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(ProcessingTimeoutSweeper.class);

    private final TimeOutProcessingPaymentsService timeOutProcessingPayments;

    public ProcessingTimeoutSweeper(TimeOutProcessingPaymentsService timeOutProcessingPayments) {
        this.timeOutProcessingPayments = timeOutProcessingPayments;
    }

    /**
     * The service already logs a WARN for every payment it times out, because each one is a payment
     * whose real outcome is unknown. This line is the operational counterpart -- how much the sweep
     * looked at -- and stays quiet when there was nothing to look at.
     */
    @Scheduled(
        fixedDelayString = "${paymesh.payments.processing-timeout.interval}",
        initialDelayString = "${paymesh.payments.processing-timeout.interval}"
    )
    public void sweep() {
        TimeOutProcessingPaymentsService.SweepResult result = timeOutProcessingPayments.sweep();

        if (result.examined() > 0) {
            log.info(
                "Processing timeout sweep examined={} failed={} held={} errored={}",
                result.examined(), result.failed(), result.held(), result.errored()
            );
        }
    }
}
