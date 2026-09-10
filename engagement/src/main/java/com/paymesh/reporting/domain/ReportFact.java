package com.paymesh.reporting.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Set;

/**
 * One thing that happened, flattened into the shape every report reads.
 *
 * <h2>A ROW PER EVENT, NEVER UPDATED</h2>
 *
 * The alternative -- a row per payment intent, mutated as the payment succeeds and is later refunded
 * -- has to be correct under concurrent and out-of-order delivery, and the failure mode of getting
 * that wrong is a silently understated total rather than an error. This never reads before it
 * writes, so there is nothing to race on. Corrections arrive as further facts, which is how the
 * Ledger corrects itself too (ADR-018).
 *
 * <h2>A record, not a mutable aggregate</h2>
 *
 * Every other capability's aggregate has intent methods because it has a state machine. This has
 * none: a fact is what a producer announced at a moment and there is no transition it can make. So
 * it is a record, per the convention that immutable carriers are records.
 *
 * @param sourceEventId the {@code evt_} that produced this row, and its primary key. A redelivery
 *     of the same event is a refused insert, which is what makes the handler idempotent without a
 *     read.
 * @param subjectId what the fact is about -- a {@code pi_}, {@code ref_} or {@code stl_}.
 *     Deliberately a plain String: Reporting must not learn six capabilities' id vocabularies to
 *     store a value it only ever echoes into a CSV column.
 * @param orderId the order a payment belongs to, or null on the settlement types
 * @param amountMinor always non-negative; direction is carried by {@link #eventType}
 * @param occurredAt the PRODUCER's clock -- what the report is about
 * @param recordedAt REPORTING's clock -- how far the projection has caught up, and the value
 *     {@code asOf} reports
 */
public record ReportFact(
    String sourceEventId,
    MerchantId merchantId,
    String eventType,
    String subjectId,
    String orderId,
    String currency,
    long amountMinor,
    Instant occurredAt,
    Instant recordedAt
) {

    /**
     * The types this projection stores, and the same set {@code ck_report_facts_event_type}
     * enforces. Widening one without the other is caught by {@code ReportingConfigurationTest},
     * which is deliberate: the constraint is what makes the set true and this is what makes it
     * readable.
     *
     * <p>{@code order.paid} is absent for the reason Notification omits it -- it is Order's
     * restatement of {@code payment.succeeded}, so counting both would double every collection.
     */
    public static final Set<String> SUBSCRIBED_TYPES = Set.of(
        "payment.succeeded",
        "payment.failed",
        "refund.succeeded",
        "settlement.batch_cut",
        "payout.paid",
        "payout.returned"
    );

    /** The subset the payment summary counts. */
    public static final Set<String> PAYMENT_TYPES =
        Set.of("payment.succeeded", "payment.failed", "refund.succeeded");

    /** The subset the settlement summary counts. */
    public static final Set<String> SETTLEMENT_TYPES =
        Set.of("settlement.batch_cut", "payout.paid", "payout.returned");

    public ReportFact {
        requireText(sourceEventId, "source event id");
        requireText(eventType, "event type");
        requireText(subjectId, "subject id");
        requireText(currency, "currency");

        if (merchantId == null) {
            throw new IllegalArgumentException("A report fact needs a merchant");
        }

        if (!SUBSCRIBED_TYPES.contains(eventType)) {
            throw new IllegalArgumentException(
                "Reporting does not project " + eventType
                    + "; ck_report_facts_event_type would refuse the row"
            );
        }

        if (amountMinor < 0) {
            throw new IllegalArgumentException(
                "A report fact amount cannot be negative, got " + amountMinor
                    + "; direction is carried by the event type"
            );
        }

        if (occurredAt == null || recordedAt == null) {
            throw new IllegalArgumentException("A report fact needs both timestamps");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A report fact " + field + " cannot be blank");
        }
    }
}
