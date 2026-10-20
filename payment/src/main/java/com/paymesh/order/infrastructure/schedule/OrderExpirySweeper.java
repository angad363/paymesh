package com.paymesh.order.infrastructure.schedule;

import com.paymesh.order.application.ExpireOrdersService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else.
 *
 * <h2>Why this class is almost empty</h2>
 *
 * A scheduled job is a framework component and may carry framework annotations -- CLAUDE.md's rule
 * about {@code final} un-annotated services is about application logic, and {@code @Scheduled} has
 * no meaning outside a container. <b>But that is exactly why no logic may live here.</b> Anything
 * inside a scheduled method can only be exercised by starting a context and waiting for a clock to
 * tick, which is a slow test that passes for the wrong reasons and fails for unrelated ones. Every
 * rule the sweep applies lives in {@link ExpireOrdersService}, which is an ordinary object taking an
 * injected {@code Clock}.
 * <p>
 * The whole body is one call and one log line. If a condition, a loop or a decision ever appears
 * here, it is in the wrong file.
 *
 * <h2>fixedDelay, not fixedRate</h2>
 *
 * The next sweep starts a fixed gap after the previous one FINISHES. With {@code fixedRate} a sweep
 * that outran its interval -- a large backlog, a slow database -- would be re-entered while still
 * running, and two overlapping sweeps contend on the same order rows for no benefit. The work is
 * catch-up work; it does not need to happen at a particular instant, only eventually.
 * <p>
 * Overlapping runs would still be CORRECT if they happened -- each order is decided under its own
 * row lock and re-checked before any write -- but correct and pointless is still pointless.
 */
public final class OrderExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirySweeper.class);

    private final ExpireOrdersService expireOrders;

    public OrderExpirySweeper(ExpireOrdersService expireOrders) {
        this.expireOrders = expireOrders;
    }

    /**
     * Logged at INFO only when it did something, so an idle platform does not write a line every few
     * minutes and a real expiry is not buried among them.
     */
    @Scheduled(
        fixedDelayString = "${paymesh.orders.expiry-sweep.interval}",
        initialDelayString = "${paymesh.orders.expiry-sweep.interval}"
    )
    public void sweep() {
        ExpireOrdersService.SweepResult result = expireOrders.sweep();

        if (result.examined() > 0) {
            log.info(
                "Order expiry sweep examined={} expired={} held={} failed={}",
                result.examined(), result.expired(), result.held(), result.failed()
            );
        }
    }
}
