package com.paymesh.shared.outbox.infrastructure.kafka;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
 * @param merchantId the tenant, present on every event ABOUT a merchant. {@code null} for a
 *                   platform-scoped action with no tenant to carry ({@code
 *                   identity.user_access.audited} for a platform role grant, ADR-043) -- mirrors
 *                   {@link OutboxEvent}'s own nullable {@code merchantId}.
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

    /** Kafka's own rule for a topic name, minus the dot and the empty string. */
    private static final Pattern LEGAL_TOPIC = Pattern.compile("[a-z0-9][a-z0-9-]{0,248}");

    public static EventEnvelope from(OutboxEvent event) {
        return new EventEnvelope(
            event.eventId().value(),
            event.eventType(),
            event.eventVersion(),
            event.occurredAt(),
            event.merchantId() == null ? null : event.merchantId().value(),
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
            merchantId == null ? null : MerchantId.from(merchantId),
            aggregateType,
            aggregateId,
            eventType,
            eventVersion,
            payload,
            occurredAt
        );
    }

    /**
     * The topic this event belongs on: <b>the aggregate type, lowercased, with underscores
     * hyphenated, plus {@code -events}</b>. {@code ORDER} to {@code order-events},
     * {@code PAYMENT_INTENT} to {@code payment-intent-events}, {@code SETTLEMENT_BATCH} to
     * {@code settlement-batch-events}.
     *
     * <h2>THE AGGREGATE, NOT THE EVENT TYPE'S PREFIX, AND THE DIFFERENCE IS AN ORDERING BUG</h2>
     *
     * This rule first read the domain prefix of {@code eventType} ({@code payment.succeeded} to
     * {@code payment-events}), which is what the Phase 3 plan's illustrative topic list looks like.
     * It is wrong, and Settlement is the proof: one {@code SETTLEMENT_BATCH} aggregate emits
     * {@code settlement.batch_cut} AND {@code payout.paid}/{@code payout.returned}, so the prefix
     * rule put one aggregate's stream on two topics. {@link #partitionKey()} is the same {@code stl_}
     * id for all three, but <b>a key only orders within a topic</b> -- across two, nothing is
     * ordered at all. The Ledger consumes all three ({@code SettlementLedgerHandler}) and they are
     * causally chained: {@code batch_cut} moves available to in-transit and {@code payout.paid}
     * discharges in-transit. Delivered in the wrong order the Ledger discharges funds it never moved
     * there, which the relay's oldest-first pass makes impossible today.
     * <p>
     * So the topic is derived from the same thing the key is derived from. <b>One aggregate, one
     * topic, one key space</b> -- and then per-aggregate ordering is a property of the naming rule
     * rather than a coincidence about which event types happen to share a prefix. It also matches
     * the plan's own words ("topics per aggregate, not per service"); only its example names assumed
     * the aggregate and the event prefix were the same string.
     *
     * <h2>Derived, not configured</h2>
     *
     * A registry mapping types to topics would be a second place to update and a second place to
     * forget; adding an event type should require no topic configuration at all, and this is the
     * only place a topic name exists.
     *
     * @throws IllegalArgumentException when the aggregate type cannot form a legal Kafka topic name.
     *     {@code aggregateType} is free text as far as the outbox is concerned (deliberately -- see
     *     {@link OutboxEvent}), so this is the boundary that has to say so.
     */
    public String topic() {
        String name = aggregateType.toLowerCase(Locale.ROOT).replace('_', '-') + TOPIC_SUFFIX;

        if (!LEGAL_TOPIC.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Aggregate type '" + aggregateType + "' does not form a legal topic name"
            );
        }

        return name;
    }

    /**
     * The Kafka record key, and therefore the partition: <b>the aggregate id</b>.
     * <p>
     * Kafka promises ordering within a partition of one topic and nothing else, so the key chooses
     * what is ordered -- given that {@link #topic()} has already put the whole aggregate on one
     * topic for it to order within. The aggregate is the right grain because it is the only ordering the money
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
