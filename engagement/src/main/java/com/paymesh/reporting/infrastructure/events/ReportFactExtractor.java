package com.paymesh.reporting.infrastructure.events;

import com.paymesh.reporting.domain.ReportFact;

import java.util.Map;

/**
 * Reads one event's payload into the flat shape {@code report_facts} stores.
 *
 * <h2>A Map, never a producer's type, and that is what keeps Reporting a leaf</h2>
 *
 * The field names below are the ones the producers write -- the same set
 * {@code WebhookPayloadTranslator} and {@code NotificationTemplates} read. Nothing here imports a
 * Payment, Refund or Settlement type, which is what lets {@code ModuleBoundaryTest} keep an empty
 * allowlist while Reporting consumes six of their events. Money is read through {@link Number}
 * because JSONB hands back an {@code Integer} for anything that fits in 32 bits.
 *
 * <h2>THE TIMESTAMP COMES FROM THE ENVELOPE, NOT THE PAYLOAD</h2>
 *
 * Deliberately. {@code payment.failed} has two producers that spell their timestamp differently
 * ({@code occurredAt} vs {@code failedAt}) -- the divergence {@code WebhookPayloadTranslator} has to
 * resolve through both keys. The envelope's {@code occurredAt} is required by {@code OutboxEvent}
 * itself, so reading it costs no per-type knowledge and cannot be null for any of the six types
 * here. Which is fortunate, because a report bucketed on a null date is a report of nothing.
 *
 * <h2>WHICH AMOUNT, PER TYPE, AND IT IS NOT ALWAYS amountMinor</h2>
 *
 * {@code payment.succeeded} reports {@code capturedAmountMinor}: a partial capture collects less
 * than the intent was for, and a summary of what a merchant COLLECTED must say the smaller number.
 * {@code payment.failed} has nothing captured, so it reports what was attempted.
 */
final class ReportFactExtractor {

    /** The flat fields a fact needs, minus the two clocks and the merchant. */
    record Extracted(String subjectId, String orderId, String currency, long amountMinor) {
    }

    private ReportFactExtractor() {
    }

    static Extracted extract(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "payment.succeeded" -> new Extracted(
                requireText(payload, "paymentIntentId"),
                text(payload, "orderId"),
                requireText(payload, "currency"),
                requireAmount(payload, "capturedAmountMinor")
            );
            case "payment.failed" -> new Extracted(
                requireText(payload, "paymentIntentId"),
                text(payload, "orderId"),
                requireText(payload, "currency"),
                requireAmount(payload, "amountMinor")
            );
            // No orderId: a refund's payload names the payment it reverses, not the order. Joining
            // through the payment to find one would be a read of Payment's table, which is the one
            // thing this capability must never do.
            case "refund.succeeded" -> new Extracted(
                requireText(payload, "refundId"),
                null,
                requireText(payload, "currency"),
                requireAmount(payload, "amountMinor")
            );
            case "settlement.batch_cut", "payout.paid", "payout.returned" -> new Extracted(
                requireText(payload, "settlementBatchId"),
                null,
                requireText(payload, "currency"),
                requireAmount(payload, "amountMinor")
            );
            default -> throw new IllegalArgumentException(
                "Reporting does not know how to project " + eventType
            );
        };
    }

    /** The types this class can read. Paired with {@link ReportFact#SUBSCRIBED_TYPES} by a test. */
    static boolean canExtract(String eventType) {
        return ReportFact.SUBSCRIBED_TYPES.contains(eventType);
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);

        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String requireText(Map<String, Object> payload, String key) {
        String value = text(payload, key);

        if (value == null) {
            throw new IllegalArgumentException("Event carries no " + key);
        }

        return value;
    }

    private static long requireAmount(Map<String, Object> payload, String key) {
        if (!(payload.get(key) instanceof Number number)) {
            throw new IllegalArgumentException("Event carries no numeric " + key);
        }

        return number.longValue();
    }
}
