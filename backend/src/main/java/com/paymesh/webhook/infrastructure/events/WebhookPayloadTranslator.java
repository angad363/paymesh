package com.paymesh.webhook.infrastructure.events;

import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Turns PayMesh's internal event into the merchant's, once, into the bytes that get signed.
 *
 * <h2>THE ENVELOPE, PINNED HERE BECAUSE A MERCHANT PARSES IT</h2>
 *
 * <pre>{@code
 * {"id":"whv_...","type":"payment.succeeded","schemaVersion":1,
 *  "occurredAt":"2026-08-05T10:00:00Z","data":{...}}
 * }</pre>
 *
 * Key order is insertion order and the maps below are {@link LinkedHashMap}, so the bytes are a
 * function of the event alone. That is not cosmetic: the merchant's HMAC covers these bytes, and
 * {@code WebhookPayloadTranslatorTest} asserts them as literal strings for exactly that reason.
 *
 * <h2>WHAT IS DELIBERATELY NOT ON THE WIRE</h2>
 *
 * {@code previousStatus} and {@code captureMethod} are PayMesh's state machine, not the merchant's
 * business (SDD §18.2 -- the external shape must survive an internal refactor). {@code merchantId}
 * is implied by the endpoint the delivery is going to. {@code providerReference} names the acquirer,
 * which is PayMesh's routing decision and not a fact the merchant integrated against.
 *
 * <h2>ONE PAYMENT SHAPE FOR BOTH OUTCOMES, AND TWO PRODUCERS THAT DISAGREE</h2>
 *
 * {@code payment.failed} is emitted from two places with two different key sets:
 * {@code RecordProviderCallbackService} writes {@code occurredAt} and no failure text,
 * {@code TimeOutProcessingPaymentsService} writes {@code failedAt} plus {@code failureCode} and
 * {@code failureMessage}. So the timestamp is resolved through both keys and the failure fields are
 * optional. Reading only one of them would emit a null timestamp to half of all merchants.
 */
public final class WebhookPayloadTranslator {

    /**
     * The types that reach a merchant. Adding one here without adding an
     * {@link WebhookFanOutHandler} bean for it does nothing -- the bean is what subscribes.
     */
    private static final Map<String, BiFunction<Map<String, Object>, OutboxEvent, Map<String, Object>>>
        TRANSLATORS = Map.of(
            "payment.succeeded", WebhookPayloadTranslator::paymentData,
            "payment.failed", WebhookPayloadTranslator::paymentData,
            "refund.succeeded", WebhookPayloadTranslator::refundData,
            "order.paid", WebhookPayloadTranslator::orderData
        );

    public static final Set<String> PUBLISHED_TYPES = TRANSLATORS.keySet();

    private final ObjectMapper objectMapper;

    public WebhookPayloadTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param webhookEventId minted by the caller, because it goes inside the bytes as {@code id} and
     *     the same value keys the {@code webhook_events} row those bytes are stored in
     * @throws IllegalArgumentException on a type nothing translates, which would otherwise be
     *     delivered as an empty object
     */
    public String translate(WebhookEventId webhookEventId, OutboxEvent event) {
        BiFunction<Map<String, Object>, OutboxEvent, Map<String, Object>> translator =
            TRANSLATORS.get(event.eventType());

        if (translator == null) {
            throw new IllegalArgumentException(
                "No webhook translation is defined for " + event.eventType()
            );
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", webhookEventId.value());
        envelope.put("type", event.eventType());
        envelope.put("schemaVersion", WebhookEvent.CURRENT_SCHEMA_VERSION);
        envelope.put("occurredAt", occurredAt(event.payload(), event).toString());
        envelope.put("data", translator.apply(event.payload(), event));

        return objectMapper.writeValueAsString(envelope);
    }

    private static Map<String, Object> paymentData(Map<String, Object> payload, OutboxEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentIntentId", requireText(payload, "paymentIntentId", event));
        data.put("orderId", text(payload, "orderId"));
        data.put("customerId", text(payload, "customerId"));
        data.put("amountMinor", requireAmount(payload, "amountMinor", event));
        data.put("capturedAmountMinor", requireAmount(payload, "capturedAmountMinor", event));
        data.put("currency", requireText(payload, "currency", event));
        data.put("status", requireText(payload, "status", event));
        data.put("failureCode", text(payload, "failureCode"));
        data.put("failureMessage", text(payload, "failureMessage"));

        return data;
    }

    private static Map<String, Object> refundData(Map<String, Object> payload, OutboxEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", requireText(payload, "refundId", event));
        data.put("paymentIntentId", requireText(payload, "paymentIntentId", event));
        data.put("amountMinor", requireAmount(payload, "amountMinor", event));
        data.put("currency", requireText(payload, "currency", event));
        data.put("merchantReference", text(payload, "merchantReference"));
        data.put("status", requireText(payload, "status", event));

        return data;
    }

    private static Map<String, Object> orderData(Map<String, Object> payload, OutboxEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", requireText(payload, "orderId", event));
        data.put("customerId", text(payload, "customerId"));
        data.put("merchantOrderReference", text(payload, "merchantOrderReference"));
        data.put("amountMinor", requireAmount(payload, "amountMinor", event));
        data.put("amountPaidMinor", requireAmount(payload, "amountPaidMinor", event));
        data.put("currency", requireText(payload, "currency", event));
        data.put("status", requireText(payload, "status", event));

        return data;
    }

    /**
     * The authority's clock when the payload carries one, PayMesh's otherwise.
     * <p>
     * {@code failedAt} is here because one of the two {@code payment.failed} producers spells it
     * that way. Dropping the alias would leave those events timestamped by the envelope, which on a
     * late-recorded failure is a genuinely different instant.
     */
    private static Instant occurredAt(Map<String, Object> payload, OutboxEvent event) {
        String stated = text(payload, "occurredAt");

        if (stated == null) {
            stated = text(payload, "failedAt");
        }

        return stated == null ? event.occurredAt() : Instant.parse(stated);
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);

        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String requireText(Map<String, Object> payload, String key, OutboxEvent event) {
        String value = text(payload, key);

        if (value == null) {
            throw new IllegalArgumentException(event.eventType() + " carries no " + key);
        }

        return value;
    }

    /** Through {@link Number}: JSONB hands back an Integer for anything that fits in 32 bits. */
    private static long requireAmount(Map<String, Object> payload, String key, OutboxEvent event) {
        if (!(payload.get(key) instanceof Number number)) {
            throw new IllegalArgumentException(event.eventType() + " carries no numeric " + key);
        }

        return number.longValue();
    }
}
