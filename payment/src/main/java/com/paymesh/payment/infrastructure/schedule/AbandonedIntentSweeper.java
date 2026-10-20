package com.paymesh.payment.infrastructure.schedule;

import com.paymesh.payment.application.CancelAbandonedPaymentIntentsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer for {@link CancelAbandonedPaymentIntentsService}.
 * <p>
 * A true framework component, so it may carry annotations -- but it holds no logic. Everything it
 * calls is a plain application service that a test, or a future admin endpoint, can invoke without a
 * scheduler in the picture.
 */
public final class AbandonedIntentSweeper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedIntentSweeper.class);

    private final CancelAbandonedPaymentIntentsService cancelAbandonedPaymentIntents;

    public AbandonedIntentSweeper(
        CancelAbandonedPaymentIntentsService cancelAbandonedPaymentIntents
    ) {
        this.cancelAbandonedPaymentIntents = cancelAbandonedPaymentIntents;
    }

    /**
     * INFO rather than the WARN its PROCESSING counterpart uses, and the difference is the point: an
     * abandoned checkout is ordinary customer behaviour, not an anomaly. Quiet when there was nothing
     * to do, so the line means something when it appears.
     */
    @Scheduled(
        fixedDelayString = "${paymesh.payments.abandoned-intents.interval}",
        initialDelayString = "${paymesh.payments.abandoned-intents.interval}"
    )
    public void sweep() {
        CancelAbandonedPaymentIntentsService.SweepResult result =
            cancelAbandonedPaymentIntents.sweep();

        if (result.examined() > 0) {
            log.info(
                "Abandoned intent sweep examined={} cancelled={} held={} errored={}",
                result.examined(), result.cancelled(), result.held(), result.errored()
            );
        }
    }
}
