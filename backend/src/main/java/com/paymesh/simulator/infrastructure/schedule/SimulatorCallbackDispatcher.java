package com.paymesh.simulator.infrastructure.schedule;

import com.paymesh.simulator.application.DispatchProviderCallbacksService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else.
 *
 * <h2>Why this class is almost empty</h2>
 *
 * A scheduled job is a framework component and may carry framework annotations -- the rule about
 * {@code final} un-annotated services is about application logic, and {@code @Scheduled} has no
 * meaning outside a container. <b>But that is exactly why no logic may live here.</b> Anything
 * inside a scheduled method can only be exercised by starting a context and waiting for a clock to
 * tick, which is a slow test that passes for the wrong reasons and fails for unrelated ones. Every
 * rule the dispatch applies lives in {@link DispatchProviderCallbacksService}, an ordinary object
 * taking an injected {@code Clock}, which every test drives directly.
 * <p>
 * The whole body is one call and one log line. If a condition, a loop or a decision ever appears
 * here, it is in the wrong file. Copied deliberately from {@code OrderExpirySweeper}, which is the
 * reviewed shape for this in the codebase.
 *
 * <h2>fixedDelay, not fixedRate</h2>
 *
 * The next pass starts a fixed gap after the previous one FINISHES. With {@code fixedRate} a pass
 * that outran its interval -- a slow receiver, a backlog -- would be re-entered while still running.
 * Overlapping runs would still be CORRECT, because each callback is claimed under
 * {@code FOR UPDATE SKIP LOCKED} and a claimed row is skipped rather than delivered twice, but
 * correct and pointless is still pointless.
 */
public final class SimulatorCallbackDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SimulatorCallbackDispatcher.class);

    private final DispatchProviderCallbacksService dispatchProviderCallbacks;

    public SimulatorCallbackDispatcher(DispatchProviderCallbacksService dispatchProviderCallbacks) {
        this.dispatchProviderCallbacks = dispatchProviderCallbacks;
    }

    /**
     * Logged at INFO only when it did something, so an idle platform does not write a line every few
     * seconds and a real delivery is not buried among them.
     */
    @Scheduled(
        fixedDelayString = "${paymesh.simulator.dispatch.interval}",
        initialDelayString = "${paymesh.simulator.dispatch.interval}"
    )
    public void dispatch() {
        DispatchProviderCallbacksService.DispatchResult result = dispatchProviderCallbacks.dispatch();

        if (result.examined() > 0) {
            log.info(
                "Provider callback dispatch examined={} delivered={} retried={} abandoned={}",
                result.examined(), result.delivered(), result.retried(), result.abandoned()
            );
        }
    }
}
