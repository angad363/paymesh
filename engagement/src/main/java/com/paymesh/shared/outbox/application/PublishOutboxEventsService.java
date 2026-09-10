package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The relay: reads the outbox backlog, delivers it, and records what was delivered (ADR-016).
 *
 * <h2>It is a plain object, and the scheduler is somewhere else</h2>
 *
 * No {@code @Scheduled}, no {@code @Component}, no Spring annotation of any kind.
 * {@code OutboxRelay} owns the timer and does nothing but call {@link #publish()} and log the
 * result, so every rule below is testable by calling one method with a fixed {@link Clock} and no
 * scheduler in the picture. This is the same split {@code OrderExpirySweeper} /
 * {@code ExpireOrdersService} already uses.
 *
 * <h2>MAPPING HAPPENS INSIDE THE TRY, AND THAT IS THE MOST DELIBERATE LINE IN THE CLASS</h2>
 *
 * Open item 2 in {@code docs/project-status.md} describes the bug both existing sweeps have: they map
 * every candidate row through the aggregate inside the repository call -- OUTSIDE the per-item
 * try/catch -- so one unmappable row throws out of the whole pass, and because the ordering is
 * oldest-first it sits at the head of every batch and disables the job permanently and silently.
 * <p>
 * {@link OutboxReader#findUnpublished} therefore returns raw {@link UnpublishedEvent} rows with no
 * validation, and {@link UnpublishedEvent#toEvent()} is called below inside the try. A corrupt row
 * costs one iteration; the rest of the batch still drains.
 *
 * <h2>Ordering, and the one thing it costs</h2>
 *
 * Events come back {@code occurred_at} ascending and are dispatched one at a time, so two events for
 * one aggregate reach a consumer in the order they happened. <b>A failed event blocks its own
 * aggregate for the rest of the pass</b> ({@link #poisonedAggregates}) -- otherwise the very first
 * failure would silently deliver an aggregate's later events before its earlier one, which is worse
 * than not delivering them at all.
 * <p>
 * <h2>THE RETRY BUDGET IS WHAT STOPS THAT BEING FOREVER (ADR-025)</h2>
 *
 * Until V21 the paragraph above ended badly: an event that failed FOREVER froze its aggregate's
 * later events forever, retried at the head of every pass, its successors skipped every pass,
 * visible only as a WARN. Open item 14 called that the largest hole in event delivery.
 * <p>
 * Every failure now increments {@code attempt_count} and records its message, and the attempt that
 * reaches {@code maxAttempts} stamps {@code dead_lettered_at}. A dead-lettered row leaves the claim
 * query, <b>so the aggregate it was blocking drains on the very next pass.</b> Note what is NOT
 * claimed: the event is not delivered and not deleted. It is retained in place, in order, and an
 * operator requeues it with {@code SET dead_lettered_at = NULL}. Trading "never delivered, loudly
 * recorded" for "never delivered, and it takes the rest of the aggregate with it" is the entire
 * decision, and it only works because the loudness is real -- see
 * {@code OutboxBacklogHealthIndicator}.
 *
 * <h2>Transactions</h2>
 *
 * This class opens exactly one, around the {@code published_at} stamp. The dispatcher opens its own
 * per handler, and they are deliberately not merged: see {@link EventDispatcher}. The stamp
 * committing separately from the handlers is what makes delivery at-least-once, and the inbox is
 * what makes at-least-once safe.
 */
public final class PublishOutboxEventsService {

    private static final Logger log = LoggerFactory.getLogger(PublishOutboxEventsService.class);

    private final OutboxReader reader;
    private final EventDispatcher dispatcher;
    private final EventPublisher kafkaPublisher;
    private final boolean publishToKafka;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    /**
     * @param kafkaPublisher the second sink (ADR-037). Always injected; whether it is called is
     *                       {@code publishToKafka}, so the {@code KafkaEventPublisher} bean can stay
     *                       unconditional (ADR-036) while the mode flag decides the behaviour.
     * @param publishToKafka {@code true} in {@code both} mode, {@code false} in {@code in-process}
     *                       mode. When false this class is byte-for-byte the pre-ADR-037 relay, which
     *                       is what makes the rollback a behavioural no-op.
     */
    public PublishOutboxEventsService(
        OutboxReader reader,
        EventDispatcher dispatcher,
        EventPublisher kafkaPublisher,
        boolean publishToKafka,
        TransactionTemplate transactions,
        ObjectMapper json,
        Clock clock,
        int batchSize,
        int maxAttempts
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Outbox relay batch size must be at least 1");
        }
        // Rejected here rather than clamped. A zero or negative budget would dead-letter every event
        // on its first failure INCLUDING transient ones, which turns the safety net into the fault;
        // silently correcting it to 1 would hide a misconfiguration that costs delivery.
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Outbox relay max attempts must be at least 1");
        }

        this.reader = reader;
        this.dispatcher = dispatcher;
        this.kafkaPublisher = kafkaPublisher;
        this.publishToKafka = publishToKafka;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * ONE IN-PROCESS PASS, AND IT IS DELIBERATELY UNTOUCHED BY KAFKA (ADR-037). This is the money
     * path: it dispatches the backlog to the in-process consumers and stamps {@code published_at}. The
     * Kafka sink is a SEPARATE pass ({@link #relayToKafka}) over a SEPARATE column, so a broker outage
     * can never hold an in-process-delivered event in this claim query or defer its aggregate's later
     * events here. That separation is the whole reason the two-column design exists.
     */
    public RelayResult publish() {
        List<UnpublishedEvent> backlog = reader.findUnpublished(batchSize);

        // The aggregates that failed in THIS pass. Their later events wait rather than overtaking
        // the one that failed -- see the class javadoc. Reset every pass, so a transient failure
        // costs one pass of latency and nothing more.
        Set<String> poisonedAggregates = new HashSet<>();

        int published = 0;
        int failed = 0;
        int deferred = 0;
        int deadLettered = 0;

        for (UnpublishedEvent row : backlog) {
            if (poisonedAggregates.contains(row.aggregateId())) {
                deferred++;
                continue;
            }

            try {
                // INSIDE THE TRY. A row that cannot form a legal envelope fails here, alone -- and
                // that now includes a payload the mapper cannot read, which used to throw one layer
                // out in the repository and take the whole pass with it.
                OutboxEvent event = row.toEvent(json);

                dispatcher.dispatch(event);

                // AFTER every handler has committed, and in its own transaction. If this fails the
                // event is redelivered and each consumer's inbox row makes that a no-op.
                Instant now = Instant.now(clock);
                transactions.execute(status -> {
                    reader.markPublished(event.eventId(), now);
                    return null;
                });

                published++;
            } catch (RuntimeException failure) {
                failed++;
                poisonedAggregates.add(row.aggregateId());

                if (recordFailure(row, failure)) {
                    deadLettered++;
                }
            }
        }

        return new RelayResult(backlog.size(), published, failed, deferred, deadLettered);
    }

    /**
     * ONE KAFKA PASS, THE SECOND SINK, AND ENTIRELY INDEPENDENT OF {@link #publish} (ADR-037).
     *
     * <h2>Why it is a separate pass over a separate column, not a step inside {@code publish}</h2>
     *
     * If one {@code published_at} gated both sinks, a broker outage -- which leaves a row unpublished
     * so it retries -- would keep an already-in-process-delivered event in the oldest-first in-process
     * claim. The bounded batch then fills with in-process-done, Kafka-pending rows and newly committed
     * events are never claimed: a Kafka outage stalls the money path. Two columns, two claims, two
     * passes: in-process progresses on {@code published_at}, Kafka on {@code kafka_published_at}, and
     * neither waits on the other.
     *
     * <h2>No retry budget on this sink (ADR-037 §3)</h2>
     *
     * A broker being down is global and self-healing, not a poisoned event, so a send failure is not
     * counted against the dead-letter budget and never dead-letters: the row stays
     * {@code kafka_published_at IS NULL} and is retried until the broker takes it. It is surfaced by
     * the Kafka half of the backlog health indicator, not by an alert-worthy failure.
     *
     * <h2>ponytail: the blocking send runs on the shared relay thread</h2>
     *
     * {@code KafkaEventPublisher.publish} blocks on the broker's acknowledgement, so during an outage
     * this pass ends at the FIRST send failure (there is no point paying the block for every following
     * aggregate when the broker is down) and retries next tick. It still shares the scheduler thread
     * with {@link #publish}, so a broker outage can delay the NEXT in-process tick by one blocked send
     * -- a latency degradation, never a loss or a reorder. A dedicated Kafka relay thread is the
     * upgrade path, deferred to when a consumer actually depends on the Kafka stream (extraction).
     *
     * @return what the pass did; empty (and no query is run) when the mode is {@code in-process}.
     */
    public KafkaRelayResult relayToKafka() {
        if (!publishToKafka) {
            return new KafkaRelayResult(0, 0, 0, 0);
        }

        List<UnpublishedEvent> backlog = reader.findUnpublishedToKafka(batchSize);

        // Only for the rare unmappable row: it holds that aggregate's later events so a Kafka record
        // is never sent out of order behind one that cannot be formed. A broker failure ends the pass
        // outright (below) rather than poisoning per aggregate.
        Set<String> poisonedAggregates = new HashSet<>();

        int published = 0;
        int failed = 0;
        int deferred = 0;

        for (UnpublishedEvent row : backlog) {
            if (poisonedAggregates.contains(row.aggregateId())) {
                deferred++;
                continue;
            }

            OutboxEvent event;
            try {
                event = row.toEvent(json);
            } catch (RuntimeException mappingFailure) {
                // Unmappable: the in-process track's budget dead-letters it, which also drops it from
                // this claim (dead_lettered_at). Until then, hold its aggregate and keep draining the
                // rest -- a healthy row behind an unmappable one must still reach Kafka.
                failed++;
                poisonedAggregates.add(row.aggregateId());
                log.warn(
                    "Skipping an unmappable row on the Kafka sink eventId={} aggregateId={}",
                    row.eventId(), row.aggregateId(), mappingFailure
                );
                continue;
            }

            try {
                kafkaPublisher.publish(event);
            } catch (RuntimeException brokerFailure) {
                // The broker is unreachable. Every remaining send would block the acknowledgement
                // timeout too, so end the pass here and retry next tick rather than pay that N times
                // on the relay thread. The row stays kafka-unpublished; the budget is untouched.
                failed++;
                log.warn(
                    "Kafka sink unavailable, ending the pass early after eventId={} aggregateId={}; "
                        + "in-process delivery is unaffected and the broker is retried next tick",
                    row.eventId(), row.aggregateId(), brokerFailure
                );
                break;
            }

            Instant now = Instant.now(clock);
            transactions.execute(status -> {
                reader.markKafkaPublished(event.eventId(), now);
                return null;
            });

            published++;
        }

        return new KafkaRelayResult(backlog.size(), published, failed, deferred);
    }

    /**
     * Writes the attempt down and says whether this was the one that exhausted the budget.
     *
     * <h2>Its own transaction, and it must be</h2>
     *
     * Whatever the dispatcher opened has already rolled back by the time control reaches here --
     * that is what the exception means. Writing the counter on that transaction would roll the
     * counter back too, and the attempt would never be recorded: the relay would retry forever while
     * believing it had a retry budget, which is a worse bug than having no budget at all.
     *
     * <h2>IT SWALLOWS ITS OWN FAILURE ON PURPOSE</h2>
     *
     * If recording the attempt throws -- the database is down, which is also the likeliest reason
     * the delivery failed a moment ago -- the pass continues. Rethrowing here would let a failure in
     * the bookkeeping abort the sweep over every OTHER aggregate, which is the precise
     * one-bad-row-kills-the-job shape open item 2 records and this class was written to avoid.
     *
     * @return true when this attempt dead-lettered the row. Derived from the count the claim query
     *     already returned, so no second read: {@code attemptCount} is failures BEFORE this one, and
     *     the SQL stamps at {@code attempt_count + 1 >= maxAttempts}. The two expressions have to
     *     agree, and a test holds them to it.
     */
    private boolean recordFailure(UnpublishedEvent row, RuntimeException failure) {
        boolean exhausted = row.attemptCount() + 1 >= maxAttempts;

        try {
            Instant now = Instant.now(clock);
            transactions.execute(status -> {
                reader.recordFailedAttempt(row.eventId(), now, failure.toString(), maxAttempts);
                return null;
            });
        } catch (RuntimeException bookkeeping) {
            log.warn(
                "Could not record a failed delivery attempt eventId={}", row.eventId(), bookkeeping
            );

            // The attempt did not stick, so the budget did not move and the row will be retried.
            // Reporting it as dead-lettered would raise an alert for something that did not happen.
            return false;
        }

        if (exhausted) {
            // ERROR, not WARN, and the only ERROR this class logs. Every other failure here resolves
            // itself on the next pass; this one never will, and until an operator acts a committed
            // state change stays unannounced. If a log pipeline alerts on one line in this file,
            // it is this one.
            log.error(
                "GAVE UP on an outbox event after {} attempts -- it will NEVER be delivered until "
                    + "requeued. eventId={} eventType={} aggregateType={} aggregateId={} "
                    + "merchantId={}. Requeue with: "
                    + "UPDATE outbox_events SET dead_lettered_at = NULL, attempt_count = 0 "
                    + "WHERE event_id = '{}';",
                maxAttempts, row.eventId(), row.eventType(), row.aggregateType(), row.aggregateId(),
                row.merchantId(), row.eventId(), failure
            );

            return true;
        }

        log.warn(
            "Could not publish outbox event eventId={} eventType={} aggregateId={} attempt={}/{}",
            row.eventId(), row.eventType(), row.aggregateId(), row.attemptCount() + 1, maxAttempts,
            failure
        );

        return false;
    }

    /**
     * What one IN-PROCESS pass did.
     *
     * @param examined  how many rows the claim query returned
     * @param published how many were delivered and stamped
     * @param failed    how many threw and were logged. Non-zero here is worth an alert; the events
     *                  are retried on the next pass
     * @param deferred  how many were skipped because an earlier event of the SAME aggregate failed
     *                  in this pass. Not an error -- it is the ordering guarantee doing its job --
     *                  but a number that stays non-zero across passes means an aggregate is stuck
     * @param deadLettered how many of the failures were the LAST attempt, so the relay gave up. A
     *                  subset of {@code failed}, never larger than it. <b>Any non-zero value here is
     *                  an incident</b>: a committed state change whose event no consumer will ever
     *                  see. The counter exists so the timer can raise it without re-reading the
     *                  table
     */
    public record RelayResult(
        int examined, int published, int failed, int deferred, int deadLettered
    ) {
    }

    /**
     * What one KAFKA pass did (ADR-037). No {@code deadLettered}: the Kafka sink has no retry budget,
     * because a broker outage is not a poison (see {@link #relayToKafka}).
     *
     * @param examined  how many rows the Kafka claim query returned
     * @param published how many reached the broker and were stamped {@code kafka_published_at}
     * @param failed    how many failed -- an unmappable row, or the one broker failure that ended the
     *                  pass. Retried next tick; surfaced by the Kafka half of the backlog health
     *                  indicator rather than by the dead-letter alert
     * @param deferred  how many were held because an earlier UNMAPPABLE event of the same aggregate is
     *                  still ahead of them
     */
    public record KafkaRelayResult(int examined, int published, int failed, int deferred) {
    }
}
