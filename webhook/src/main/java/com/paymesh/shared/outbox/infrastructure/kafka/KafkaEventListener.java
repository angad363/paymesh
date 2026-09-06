package com.paymesh.shared.outbox.infrastructure.kafka;

import com.paymesh.shared.outbox.application.EventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

/**
 * The consumer half of the dual path (ADR-037): one listener, feeding the in-process dispatcher.
 *
 * <h2>ONE LISTENER, NOT ONE PER HANDLER, AND THE DISPATCHER IS WHY</h2>
 *
 * The plan says "each {@code EventHandler} gets a Kafka listener adapter." The literal reading is a
 * listener per handler; this is the identical outcome with none of the duplication.
 * {@link EventDispatcher} already routes an event to every handler subscribed to its type and
 * dedupes each through {@code processed_events} — it IS the fan-out. So a single listener that hands
 * each record to {@code dispatch} reaches every handler exactly as the relay's in-process call does,
 * and inherits, unchanged, one transaction per (handler, event), the inbox claim, and throw-to-retry.
 * N per-handler adapters would rebuild that fan-out N times over. See ADR-037 §4.
 *
 * <h2>The subscription is a pattern, so adding an aggregate needs no wiring</h2>
 *
 * Every topic is {@code <aggregate>-events} (ADR-036 §3), so {@link #TOPIC_PATTERN} matches them all.
 * A hand-maintained topic list would be a second place to forget a new aggregate type; the pattern
 * is the same "derived, not configured" instinct the topic name itself follows. New topics are
 * discovered within the consumer's {@code metadata.max.age.ms} (tuned down in {@code application.yaml}
 * so a brand-new aggregate's first events are not stranded for Kafka's five-minute default).
 *
 * <h2>Why redelivery here is a no-op, and what governs it</h2>
 *
 * In {@code both} mode the relay has already delivered this event in process, so the record the
 * listener receives finds every inbox row already claimed and does nothing — the extra work is one
 * "already processed" read per (handler, event). A handler that genuinely throws does not let the
 * container commit the offset, so the record is redelivered and retried while its siblings dedupe.
 * <p>
 * ponytail: consumer-side error handling is Spring's default {@code DefaultErrorHandler} (bounded
 * retry, then log and advance the offset). That is enough while in-process remains the authoritative
 * consumer under it (ADR-037 §4) — a record this listener gives up on was already applied in process.
 * A real dead-letter topic / infinite retry belongs to a capability once it is Kafka-ONLY, and is
 * built per service at extraction (PRs 6–14), not here.
 */
public final class KafkaEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventListener.class);

    /** Every domain topic ends in {@code -events} (ADR-036 §3); nothing else is subscribed. */
    static final String TOPIC_PATTERN = ".+-events";

    /**
     * THIS DEPLOYABLE'S OWN CONSUMER GROUP (ADR-042 section 5), not the monolith's
     * {@code paymesh-monolith}. The monolith's copy of this same class's javadoc already named this
     * day: "each capability gets its OWN group when it is extracted." Webhook is now a genuinely
     * independent second reader of the event stream, not merely compatible with the dual path
     * (ADR-037) -- the whole point of this PR. Stable by choice, same reason as the monolith's: a
     * rename replays every topic from {@code earliest}, safe (the inbox dedupes) but wasteful.
     */
    static final String CONSUMER_GROUP = "paymesh-webhook";

    private final EventDispatcher dispatcher;
    private final ObjectMapper json;

    public KafkaEventListener(EventDispatcher dispatcher, ObjectMapper json) {
        this.dispatcher = dispatcher;
        this.json = json;
    }

    /**
     * One record. Deserialize the envelope, validate it back into an {@link EventDispatcher}-shaped
     * event, dispatch. Exceptions PROPAGATE to the container's error handler — a swallowed failure
     * would commit the offset on an event nothing applied, which is the one way the Kafka path could
     * lose one.
     *
     * @param value the record body, JSON. String because the envelope is serialized by the
     *              application's own {@code ObjectMapper} (ADR-036), not a typed Kafka serializer.
     */
    @KafkaListener(topicPattern = TOPIC_PATTERN, groupId = CONSUMER_GROUP)
    public void onRecord(String value) {
        EventEnvelope envelope = json.readValue(value, EventEnvelope.class);

        log.debug(
            "Consumed event from Kafka eventId={} eventType={} aggregateId={}",
            envelope.eventId(), envelope.eventType(), envelope.aggregateId()
        );

        dispatcher.dispatch(envelope.toEvent());
    }
}
