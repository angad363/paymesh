package com.paymesh.notification.infrastructure.events;

import java.util.Map;
import java.util.Set;

/**
 * The notification copy, as code rather than a table (ADR-033). A template is static per-event
 * content that a deploy changes, exactly like Risk's rules (ADR-030); a {@code notification_templates}
 * table with no non-engineer editing it would be a table nothing writes.
 *
 * <p>Renders once, at record time, into the subject and body that get stored -- so a later edit here
 * cannot change what a merchant was already told.
 *
 * <h2>Reads the payload as a Map, and that keeps Notification a leaf</h2>
 *
 * The field names below are the ones the producers write (the same set
 * {@code WebhookPayloadTranslator} reads). Nothing here imports a Payment or Refund type;
 * {@code ModuleBoundaryTest} asserts the empty allowlist. Money is read through {@link Number}
 * because JSONB hands back an {@code Integer} for anything that fits in 32 bits.
 */
public final class NotificationTemplates {

    /**
     * The event types Notification subscribes to. {@code order.paid} is deliberately absent: it is
     * Order's restatement of {@code payment.succeeded}, so notifying on both would tell a merchant
     * they were paid twice for one collection.
     */
    public static final Set<String> SUBSCRIBED_TYPES =
        Set.of("payment.succeeded", "payment.failed", "refund.succeeded");

    /** A rendered notification: what the merchant sees. */
    public record Rendered(String subject, String body) {
    }

    public Rendered render(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "payment.succeeded" -> paymentSucceeded(payload);
            case "payment.failed" -> paymentFailed(payload);
            case "refund.succeeded" -> refundSucceeded(payload);
            default -> throw new IllegalArgumentException(
                "No notification template is defined for " + eventType
            );
        };
    }

    private static Rendered paymentSucceeded(Map<String, Object> payload) {
        String paymentIntentId = requireText(payload, "paymentIntentId");
        long amount = requireAmount(payload, "capturedAmountMinor");
        String currency = requireText(payload, "currency");

        return new Rendered(
            "Payment received",
            "Payment " + paymentIntentId + " for " + amount + " " + currency
                + " (minor units) succeeded."
        );
    }

    private static Rendered paymentFailed(Map<String, Object> payload) {
        String paymentIntentId = requireText(payload, "paymentIntentId");
        long amount = requireAmount(payload, "amountMinor");
        String currency = requireText(payload, "currency");

        // The failure fields are optional: one of the two payment.failed producers omits them
        // (see WebhookPayloadTranslator's note). Render whatever is present, nothing when neither is.
        String code = text(payload, "failureCode");
        String message = text(payload, "failureMessage");
        String detail = "";

        if (code != null && message != null) {
            detail = " (" + code + ": " + message + ")";
        } else if (code != null) {
            detail = " (" + code + ")";
        } else if (message != null) {
            detail = " (" + message + ")";
        }

        return new Rendered(
            "Payment failed",
            "Payment " + paymentIntentId + " for " + amount + " " + currency
                + " (minor units) failed." + detail
        );
    }

    private static Rendered refundSucceeded(Map<String, Object> payload) {
        String refundId = requireText(payload, "refundId");
        String paymentIntentId = requireText(payload, "paymentIntentId");
        long amount = requireAmount(payload, "amountMinor");
        String currency = requireText(payload, "currency");

        return new Rendered(
            "Refund processed",
            "Refund " + refundId + " for " + amount + " " + currency + " (minor units) on payment "
                + paymentIntentId + " was processed."
        );
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
