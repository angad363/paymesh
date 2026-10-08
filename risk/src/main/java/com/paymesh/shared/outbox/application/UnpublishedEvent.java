package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * One claimed {@code outbox_events} row, EXACTLY AS IT IS STORED AND NOT YET VALIDATED.
 *
 * <h2>Why the port does not simply return {@link OutboxEvent}</h2>
 *
 * Open item 2 in {@code docs/project-status.md} describes the bug this shape exists to avoid, in the
 * two sweepers that already have it: they map every candidate row through the aggregate <em>inside
 * the repository call</em>, which is <b>outside</b> the per-item try/catch that exists to stop one
 * bad row killing the run. A row that fails to map therefore throws out of the whole pass, and
 * because candidates come back oldest-first it sits at the head of every subsequent batch and
 * silently disables the job forever.
 * <p>
 * The relay must not reproduce that. So this record carries raw columns with <b>no validation at
 * all</b> -- a blank event id, a merchant id that will not parse and a zero version all survive
 * construction -- and {@link #toEvent} is called by the relay <em>inside</em> its per-item
 * try/catch, where a malformed row costs one iteration and nothing else.
 *
 * <h2>AND THE PAYLOAD IS A STRING, WHICH IS THE HALF THIS ORIGINALLY GOT WRONG</h2>
 *
 * This record used to carry {@code Map<String, Object> payload}, and that map was already parsed by
 * the time it arrived: the candidate query returned entities, so Hibernate deserialized the JSONB
 * column while materializing each one -- inside the repository call, outside the boundary. Every
 * word above about identifiers was true and the payload walked straight past it. A payload that is
 * not a JSON object (an array is legal JSONB and no CHECK forbids one) killed the relay exactly the
 * way a malformed id killed the sweeps, and because the query is oldest-first it stayed dead.
 * <p>
 * So the payload stays raw text until {@link #toEvent} parses it, which is the only place this row
 * is allowed to throw. Verified by {@code EventDeliveryIntegrationTest}.
 * <p>
 * It lives in {@code application} rather than {@code infrastructure} because it is what the
 * {@link OutboxReader} port returns, and a port's own package must be able to name its return type.
 * It carries no JPA type and no framework annotation.
 */
/**
 * @param attemptCount how many delivery attempts have already FAILED for this row (V21). Carried so
 *     the relay can say, without a second query, whether the failure it is about to record is the
 *     one that exhausts the retry budget -- the difference between a WARN that will resolve itself
 *     and an ERROR nobody else will ever raise. Like every other component here it is unvalidated:
 *     a negative value survives construction and is simply a number the relay compares.
 */
public record UnpublishedEvent(
    String eventId,
    String merchantId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    String payloadJson,
    Instant occurredAt,
    int attemptCount
) {

    private static final TypeReference<Map<String, Object>> PAYLOAD_SHAPE = new TypeReference<>() {
    };

    /**
     * The validating step, and THE ONLY PLACE THIS ROW IS ALLOWED TO THROW.
     * <p>
     * Parsing the payload happens here rather than in the adapter for the reason the class javadoc
     * gives: a JSON array in that column is a row the relay cannot use, and finding that out inside
     * the caller's try costs one event instead of the pass.
     *
     * @param json parses {@link #payloadJson}. Passed in rather than held statically so the relay
     *     uses the application's configured mapper and this record keeps no framework state.
     * @throws IllegalArgumentException when the stored row cannot form a legal envelope, including
     *     when the payload is absent or is not a JSON object
     */
    public OutboxEvent toEvent(ObjectMapper json) {
        return new OutboxEvent(
            EventId.from(eventId),
            MerchantId.from(merchantId),
            aggregateType,
            aggregateId,
            eventType,
            eventVersion,
            payload(json),
            occurredAt
        );
    }

    /**
     * A null payload becomes an empty map rather than throwing: {@code payload} is NOT NULL in the
     * schema, so a null is corruption, and an empty map a handler refuses is a better way to learn
     * that than a NullPointerException deep inside one. Anything non-null must parse to a JSON
     * object, and an array or scalar throws -- which is the case that used to wedge the relay.
     */
    private Map<String, Object> payload(ObjectMapper json) {
        if (payloadJson == null) {
            return Map.of();
        }

        try {
            return json.readValue(payloadJson, PAYLOAD_SHAPE);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                "Outbox event " + eventId + " has a payload that is not a JSON object", failure
            );
        }
    }
}
