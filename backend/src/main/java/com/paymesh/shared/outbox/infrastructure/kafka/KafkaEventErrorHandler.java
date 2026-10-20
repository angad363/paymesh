package com.paymesh.shared.outbox.infrastructure.kafka;

import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.ObjectMapper;

/**
 * The listener's error policy: Spring's bounded default for every event type, and an UNBOUNDED
 * retry with backoff for {@code settlement.batch_cut} alone (ADR-037, ADR-047).
 *
 * <h2>Why batch_cut, and only batch_cut</h2>
 *
 * The cut reaches the Ledger only through this listener once Settlement is its own deployable. A
 * bounded retry that gives up drops the cut, the batch stays {@code PENDING_PAYOUT} forever and its
 * funds stay frozen with no signal -- a silent loss. Every other consumer here is either idempotent
 * read-side work or still covered by the in-process path, so dropping after the bound is acceptable
 * for them and they keep the default.
 *
 * <h2>Blast radius</h2>
 *
 * A poisoned cut is retried (and logged) until fixed. Kafka orders per partition, so records behind
 * it on the SAME partition wait too; every other partition is unaffected. The poisoned batch's own
 * payout never starts (it needs {@code ledger.batch_posted}), which is the intended outcome.
 */
public final class KafkaEventErrorHandler {

    static final String BATCH_CUT = "settlement.batch_cut";

    private KafkaEventErrorHandler() {
    }

    public static DefaultErrorHandler create(ObjectMapper json) {
        ExponentialBackOff unbounded = new ExponentialBackOff(1_000L, 2.0);
        unbounded.setMaxInterval(60_000L);
        unbounded.setMaxElapsedTime(Long.MAX_VALUE);

        return create(json, null, unbounded);
    }

    /** @param recoverer null for Spring's default (log and advance), for the bounded types */
    static DefaultErrorHandler create(
        ObjectMapper json, ConsumerAwareRecordRecoverer recoverer, BackOff batchCutBackOff
    ) {
        DefaultErrorHandler handler = recoverer == null
            ? new DefaultErrorHandler()
            : new DefaultErrorHandler(recoverer);

        // null from the function means "use the handler's own (bounded) back off".
        handler.setBackOffFunction((record, failure) ->
            isBatchCut(json, record.value()) ? batchCutBackOff : null
        );

        return handler;
    }

    private static boolean isBatchCut(ObjectMapper json, Object value) {
        try {
            return value instanceof String body
                && BATCH_CUT.equals(json.readValue(body, EventEnvelope.class).eventType());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
