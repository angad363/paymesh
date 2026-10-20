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
     * The in-process pass, then the Kafka pass (ADR-037). Both logged at INFO only when there was
     * something to do -- the interval is short (delivery latency a merchant sees), so an idle platform
     * would otherwise write a line every couple of seconds and bury the pass that mattered.
     * <p>
     * The Kafka pass self-gates: under {@code in-process} mode it returns empty without touching the
     * database, so this method is byte-for-byte the pre-ADR-037 relay there. The two passes run on the
     * same scheduler thread -- see {@link PublishOutboxEventsService#relayToKafka} for the one cost of
     * that (a broker outage can delay the next in-process tick) and why it is a latency ceiling, never
     * a loss.
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

        PublishOutboxEventsService.KafkaRelayResult kafka = publishOutboxEvents.relayToKafka();

        if (kafka.examined() > 0) {
            log.info(
                "Outbox Kafka relay examined={} published={} failed={} deferred={}",
                kafka.examined(), kafka.published(), kafka.failed(), kafka.deferred()
            );
        }
    }
}
