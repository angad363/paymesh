package com.paymesh.shared.outbox.infrastructure.kafka;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Map;

/**
 * One domain event as it crosses the wire (ADR-036): the JSON body of a Kafka record.
 *
 * <h2>Why this exists when {@link OutboxEvent} already is an envelope</h2>
 *
 * It is the same seven facts, and that is the point -- Phase 3 invents no new event shape, it
 * formalizes the one the {@code outbox_events} row has carried since ADR-010. What this record adds
 * is the distinction between an INTERNAL type and an EXTERNAL contract, the one ADR-028 draws
 * between a webhook's domain model and its wire payload.
 * <p>
 * {@link OutboxEvent} holds validated value objects ({@link EventId}, {@link MerchantId}) and its
 * field names are ours to rename. This record holds plain strings, and its field names are a
 * published contract that other services will parse. Serializing the domain type directly would
 * have made every future refactor of it a breaking change to nine consumers, and would have put
 * {@code {"value":"evt_..."}} on the wire because that is what a wrapper record serializes to.
 *
 * <h2>The compatibility rule this record is governed by</h2>
 *
 * <ol>
 *   <li><b>Within a version, changes are ADDITIVE ONLY.</b> A new optional field may appear in
 *       {@code payload}; an existing field may never be removed, renamed, or have its type or
 *       meaning changed. Consumers ignore fields they do not know
 *       ({@code FAIL_ON_UNKNOWN_PROPERTIES} is off, and {@code payload} is an open map), so a
 *       producer may ship an addition without waiting for them.</li>
 *   <li><b>Anything else is a NEW VERSION</b> -- {@code eventVersion} 2 of the same
 *       {@code eventType}, emitted alongside version 1 for a transition long enough that every
 *       consumer has moved, then version 1 retired. A consumer that does not recognize a version
 *       must refuse the event (throw, so redelivery applies) rather than guess at it.</li>
 * </ol>
 *
 * {@code eventVersion} is per {@code eventType}, not global: {@code payment.succeeded} v1 and
 * {@code order.created} v3 coexist, exactly as the per-capability {@code *_VERSION} constants in
 * the producing services already do.
 *
 * @param eventId    the deduplication key. It is what a consumer's {@code processed_events} row is
 *                   keyed on, which is what makes at-least-once delivery safe (ADR-016).
 * @param merchantId the tenant. Present on every event because every table in this system is
 *                   merchant-scoped, and a consumer must be able to scope its write without a
 *                   lookup.
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String merchantId,
    String aggregateType,
    String aggregateId,
    Map<String, Object> payload
) {

    private static final String TOPIC_SUFFIX = "-events";

    public static EventEnvelope from(OutboxEvent event) {
        return new EventEnvelope(
            event.eventId().value(),
            event.eventType(),
            event.eventVersion(),
            event.occurredAt(),
            event.merchantId().value(),
            event.aggregateType(),
            event.aggregateId(),
            event.payload()
        );
    }

    /**
     * Back to the domain type, validating on the way in exactly as {@code UnpublishedEvent.toEvent}
     * does on the way out: a malformed identifier or a zero version throws here, at the boundary,
     * where a consumer's per-record error handling can see it -- rather than deeper in, inside a
     * handler that assumed the envelope was already sound.
     *
     * @throws IllegalArgumentException when the received JSON cannot form a legal event
     */
    public OutboxEvent toEvent() {
        return new OutboxEvent(
            EventId.from(eventId),
            MerchantId.from(merchantId),
            aggregateType,
            aggregateId,
            eventType,
            eventVersion,
            payload,
            occurredAt
        );
    }

    /**
     * The topic this event belongs on: <b>the first segment of {@code eventType}, plus
     * {@code -events}</b>. {@code order.created} to {@code order-events},
     * {@code payment.succeeded} to {@code payment-events},
     * {@code customer.payment_method.attached} to {@code customer-events}.
     * <p>
     * <b>Per aggregate, not per service</b>, and derived rather than configured. Per service would
     * mean renaming topics every time a capability moves between deployables, which is precisely
     * what Phase 3 does repeatedly. Derived rather than a registry means adding an event type adds
     * no configuration and cannot forget to -- the naming rule is the only place a topic name
     * exists.
     * <p>
     * It is the {@code eventType} prefix rather than {@code aggregateType} because the prefix is
     * already the domain name a reader expects ({@code payment.succeeded} on {@code payment-events})
     * while the aggregate is an implementation detail of who emits it ({@code PAYMENT_INTENT}).
     * {@code eventType} is NOT NULL and CHECKed non-blank in {@code outbox_events}, so the prefix is
     * always there; an event type with no dot is a producer bug and is refused here rather than
     * silently landing on a topic named after the whole type.
     *
     * @throws IllegalArgumentException when the event type carries no {@code domain.} prefix
     */
    public String topic() {
        int dot = eventType == null ? -1 : eventType.indexOf('.');

        if (dot <= 0) {
            throw new IllegalArgumentException(
                "Event type '" + eventType + "' has no domain prefix, so it has no topic"
            );
        }

        return eventType.substring(0, dot) + TOPIC_SUFFIX;
    }

    /**
     * The Kafka record key, and therefore the partition: <b>the aggregate id</b>.
     * <p>
     * Kafka promises ordering within a partition and nothing across partitions, so the key chooses
     * what is ordered. The aggregate is the right grain because it is the only ordering the money
     * path has ever needed -- ADR-012 already reasoned this out for provider callbacks, where two
     * callbacks about ONE payment must not overtake each other and callbacks about two different
     * payments have no relationship at all.
     * <p>
     * Not {@code merchantId}, which would serialize a whole tenant behind one partition for no
     * invariant, and would put a large merchant's entire event stream on one consumer thread.
     * <p>
     * It is not a field of the JSON body: the key is Kafka's own slot on the record, and writing it
     * twice would let the two disagree. A consumer that wants it reads {@code aggregateId}.
     */
    public String partitionKey() {
        return aggregateId;
    }
}
