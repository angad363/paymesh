package com.paymesh.shared.outbox.infrastructure.schedule;

import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else.
 * <p>
 * A scheduled job is a framework component and may carry framework annotations, but that is exactly
 * why no logic may live here: anything inside a scheduled method can only be exercised by starting a
 * context and waiting for a clock to tick, which is a slow test that passes for the wrong reasons.
 * Every rule the relay applies -- the batch bound, the per-item isolation, where mapping happens,
 * what a failure defers -- lives in {@link PublishOutboxEventsService}, an ordinary object taking an
 * injected {@code Clock}. If a condition, a loop or a decision ever appears in this file, it is in
 * the wrong file.
 * <p>
 * {@code fixedDelay} rather than {@code fixedRate}, for the reason {@code OrderExpirySweeper} gives:
 * the next pass starts a fixed gap after the previous one FINISHES, so a pass draining a large
 * backlog is never re-entered while still running. Two overlapping passes would still be CORRECT --
 * every consumer's inbox row arbitrates -- but correct and pointless is still pointless.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final PublishOutboxEventsService publishOutboxEvents;

    public OutboxRelay(PublishOutboxEventsService publishOutboxEvents) {
        this.publishOutboxEvents = publishOutboxEvents;
    }

    /**
     * Logged at INFO only when there was something to do. The interval is short -- this is delivery
     * latency a merchant sees, not catch-up work -- so an idle platform would otherwise write a line
     * every couple of seconds and bury the pass that mattered.
     */
    @Scheduled(
        fixedDelayString = "${paymesh.events.outbox-relay.interval}",
        initialDelayString = "${paymesh.events.outbox-relay.interval}"
    )
    public void publish() {
        PublishOutboxEventsService.RelayResult result = publishOutboxEvents.publish();

        if (result.examined() > 0) {
            log.info(
                "Outbox relay examined={} published={} failed={} deferred={} deadLettered={}",
                result.examined(), result.published(), result.failed(), result.deferred(),
                result.deadLettered()
            );
        }
    }
}
