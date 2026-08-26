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
     * One pass. Returns what it did, so the timer can log it and a test can assert it without
     * reading the database.
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

            OutboxEvent event;
            try {
                // THE IN-PROCESS SINK, AND ITS FAILURES ARE THE ONES THE BUDGET IS FOR. A row that
                // cannot form a legal envelope fails here too (INSIDE THE TRY -- that now includes a
                // payload the mapper cannot read, which used to throw one layer out in the repository
                // and take the whole pass with it), and so does a handler that cannot apply the event.
                // Both are poisons: content the consumer can never accept, so they consume the
                // dead-letter budget exactly as before ADR-037.
                event = row.toEvent(json);
                dispatcher.dispatch(event);
            } catch (RuntimeException failure) {
                failed++;
                poisonedAggregates.add(row.aggregateId());

                if (recordFailure(row, failure)) {
                    deadLettered++;
                }

                continue;
            }

            // THE KAFKA SINK, AND ITS FAILURES ARE A DIFFERENT KIND (ADR-037 section 3). A broker
            // being down fails every event equally and heals itself when it returns, so it is NOT a
            // poison and must NOT burn the budget: the row is left unpublished and retried next pass
            // (where the in-process dispatch above is a deduped no-op), and the age-based backlog
            // health indicator is what surfaces a broker that stays down. In-process has already
            // delivered, so nothing on the money path waits on this.
            if (publishToKafka) {
                try {
                    kafkaPublisher.publish(event);
                } catch (RuntimeException kafkaFailure) {
                    failed++;
                    poisonedAggregates.add(row.aggregateId());
                    log.warn(
                        "Could not publish to the Kafka sink; in-process delivery already applied, "
                            + "retrying the broker next pass without spending the retry budget. "
                            + "eventId={} eventType={} aggregateId={}",
                        row.eventId(), row.eventType(), row.aggregateId(), kafkaFailure
                    );

                    continue;
                }
            }

            // AFTER both sinks have taken it, and in its own transaction. If this fails the event is
            // redelivered and each consumer's inbox row makes that a no-op.
            Instant now = Instant.now(clock);
            transactions.execute(status -> {
                reader.markPublished(event.eventId(), now);
                return null;
            });

            published++;
        }

        return new RelayResult(backlog.size(), published, failed, deferred, deadLettered);
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
     * What one pass did.
     *
     * @param examined  how many rows the claim query returned
     * @param published how many were delivered and stamped
     * @param failed    how many threw and were logged, across BOTH sinks (ADR-037). Non-zero here is
     *                  worth an alert; the events are retried on the next pass. A Kafka-sink failure
     *                  is counted here but never feeds {@code deadLettered} -- so the invariant
     *                  {@code deadLettered <= failed} still holds and only in-process poisons ever
     *                  exhaust the budget
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
}
