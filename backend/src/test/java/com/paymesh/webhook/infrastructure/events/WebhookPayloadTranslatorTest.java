package com.paymesh.webhook.infrastructure.events;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.WebhookEventId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE STABILITY TEST, AND IT ASSERTS BYTES RATHER THAN FIELDS.
 *
 * <p>These strings are the merchant's contract. A merchant's verifier computes an HMAC over exactly
 * what arrived, so a renamed field, a reordered key or a newly-included internal value is a breaking
 * change even when the JSON is "equivalent". Comparing parsed objects would pass through every one
 * of those. So the expected values below are written out in full, on purpose, and a diff here is the
 * question "did you mean to change the public schema?" being asked out loud.
 *
 * <p>A plain {@link JsonMapper} rather than the application's: every value crossing this boundary is
 * a String, a long or an int -- timestamps are pre-formatted precisely so no date module is involved
 * -- so the two cannot disagree, and the test stays context-free per the repo's testing convention.
 */
class WebhookPayloadTranslatorTest {

    private static final WebhookEventId EVENT_ID =
        WebhookEventId.from("whv_11111111-1111-4111-8111-111111111111");

    private static final MerchantId MERCHANT =
        MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000");

    private static final String INTENT = "pi_22222222-2222-4222-8222-222222222222";
    private static final String ORDER = "ord_33333333-3333-4333-8333-333333333333";
    private static final String CUSTOMER = "cus_44444444-4444-4444-8444-444444444444";
    private static final String REFUND = "ref_55555555-5555-4555-8555-555555555555";

    private static final Instant RECORDED = Instant.parse("2026-08-05T10:00:00Z");
    private static final Instant AUTHORITY = Instant.parse("2026-08-05T09:59:12Z");

    private final WebhookPayloadTranslator translator =
        new WebhookPayloadTranslator(JsonMapper.builder().build());

    @Test
    void translatesPaymentSucceeded() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", INTENT);
        payload.put("merchantId", MERCHANT.value());
        payload.put("orderId", ORDER);
        payload.put("customerId", CUSTOMER);
        payload.put("amountMinor", 500000);
        payload.put("capturedAmountMinor", 500000);
        payload.put("currency", "INR");
        payload.put("captureMethod", "AUTOMATIC");
        payload.put("previousStatus", "PROCESSING");
        payload.put("status", "SUCCEEDED");
        payload.put("occurredAt", AUTHORITY.toString());

        assertThat(translator.translate(EVENT_ID, event("payment.succeeded", payload)))
            .isEqualTo("""
                {"id":"whv_11111111-1111-4111-8111-111111111111",\
                "type":"payment.succeeded","schemaVersion":1,\
                "occurredAt":"2026-08-05T09:59:12Z",\
                "data":{"paymentIntentId":"pi_22222222-2222-4222-8222-222222222222",\
                "orderId":"ord_33333333-3333-4333-8333-333333333333",\
                "customerId":"cus_44444444-4444-4444-8444-444444444444",\
                "amountMinor":500000,"capturedAmountMinor":500000,"currency":"INR",\
                "status":"SUCCEEDED","failureCode":null,"failureMessage":null}}""");
    }

    /**
     * The provider-callback producer's {@code payment.failed}: {@code occurredAt}, no failure text.
     * The other producer's shape is the next test, and they must translate to the same schema.
     */
    @Test
    void translatesPaymentFailedFromTheProviderCallbackProducer() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", INTENT);
        payload.put("orderId", ORDER);
        payload.put("customerId", null);
        payload.put("amountMinor", 500000);
        payload.put("capturedAmountMinor", 0);
        payload.put("currency", "INR");
        payload.put("status", "FAILED");
        payload.put("occurredAt", AUTHORITY.toString());

        assertThat(translator.translate(EVENT_ID, event("payment.failed", payload)))
            .isEqualTo("""
                {"id":"whv_11111111-1111-4111-8111-111111111111",\
                "type":"payment.failed","schemaVersion":1,\
                "occurredAt":"2026-08-05T09:59:12Z",\
                "data":{"paymentIntentId":"pi_22222222-2222-4222-8222-222222222222",\
                "orderId":"ord_33333333-3333-4333-8333-333333333333","customerId":null,\
                "amountMinor":500000,"capturedAmountMinor":0,"currency":"INR",\
                "status":"FAILED","failureCode":null,"failureMessage":null}}""");
    }

    /**
     * THE TIMEOUT PRODUCER SPELLS THE TIMESTAMP {@code failedAt}, and reading only
     * {@code occurredAt} would silently substitute the envelope's clock on every timed-out payment.
     */
    @Test
    void translatesPaymentFailedFromTheTimeoutProducerWhichNamesTheTimestampDifferently() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", INTENT);
        payload.put("orderId", ORDER);
        payload.put("customerId", CUSTOMER);
        payload.put("amountMinor", 500000);
        payload.put("capturedAmountMinor", 0);
        payload.put("currency", "INR");
        payload.put("status", "FAILED");
        payload.put("failureCode", "PROCESSING_TIMEOUT");
        payload.put("failureMessage", "No provider outcome arrived in time");
        payload.put("failedAt", AUTHORITY.toString());

        assertThat(translator.translate(EVENT_ID, event("payment.failed", payload)))
            .isEqualTo("""
                {"id":"whv_11111111-1111-4111-8111-111111111111",\
                "type":"payment.failed","schemaVersion":1,\
                "occurredAt":"2026-08-05T09:59:12Z",\
                "data":{"paymentIntentId":"pi_22222222-2222-4222-8222-222222222222",\
                "orderId":"ord_33333333-3333-4333-8333-333333333333",\
                "customerId":"cus_44444444-4444-4444-8444-444444444444",\
                "amountMinor":500000,"capturedAmountMinor":0,"currency":"INR",\
                "status":"FAILED","failureCode":"PROCESSING_TIMEOUT",\
                "failureMessage":"No provider outcome arrived in time"}}""");
    }

    @Test
    void translatesRefundSucceeded() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", REFUND);
        payload.put("merchantId", MERCHANT.value());
        payload.put("paymentIntentId", INTENT);
        payload.put("amountMinor", 250000);
        payload.put("currency", "INR");
        payload.put("merchantReference", "order-4417-partial");
        payload.put("providerReference", "sim_ref_9f2");
        payload.put("failureCode", null);
        payload.put("previousStatus", "PROCESSING");
        payload.put("status", "SUCCEEDED");
        payload.put("occurredAt", AUTHORITY.toString());

        assertThat(translator.translate(EVENT_ID, event("refund.succeeded", payload)))
            .isEqualTo("""
                {"id":"whv_11111111-1111-4111-8111-111111111111",\
                "type":"refund.succeeded","schemaVersion":1,\
                "occurredAt":"2026-08-05T09:59:12Z",\
                "data":{"refundId":"ref_55555555-5555-4555-8555-555555555555",\
                "paymentIntentId":"pi_22222222-2222-4222-8222-222222222222",\
                "amountMinor":250000,"currency":"INR",\
                "merchantReference":"order-4417-partial","status":"SUCCEEDED"}}""");
    }

    @Test
    void translatesOrderPaid() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", ORDER);
        payload.put("merchantId", MERCHANT.value());
        payload.put("customerId", CUSTOMER);
        payload.put("merchantOrderReference", null);
        payload.put("amountMinor", 500000);
        payload.put("amountPaidMinor", 500000);
        payload.put("currency", "INR");
        payload.put("previousStatus", "PENDING");
        payload.put("status", "PAID");
        payload.put("occurredAt", AUTHORITY.toString());

        assertThat(translator.translate(EVENT_ID, event("order.paid", payload)))
            .isEqualTo("""
                {"id":"whv_11111111-1111-4111-8111-111111111111",\
                "type":"order.paid","schemaVersion":1,\
                "occurredAt":"2026-08-05T09:59:12Z",\
                "data":{"orderId":"ord_33333333-3333-4333-8333-333333333333",\
                "customerId":"cus_44444444-4444-4444-8444-444444444444",\
                "merchantOrderReference":null,"amountMinor":500000,\
                "amountPaidMinor":500000,"currency":"INR","status":"PAID"}}""");
    }

    /** No payload clock at all: the envelope's, which is when PayMesh recorded it. */
    @Test
    void fallsBackToTheEnvelopeTimestamp() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", ORDER);
        payload.put("amountMinor", 1);
        payload.put("amountPaidMinor", 1);
        payload.put("currency", "INR");
        payload.put("status", "PAID");

        assertThat(translator.translate(EVENT_ID, event("order.paid", payload)))
            .contains("\"occurredAt\":\"2026-08-05T10:00:00Z\"");
    }

    /**
     * A type nothing translates must not be delivered as an empty object -- it throws, which under
     * {@code EventHandler}'s third rule means retry and eventually the outbox dead letter.
     */
    @Test
    void refusesAnEventTypeNothingTranslates() {
        assertThatThrownBy(() ->
            translator.translate(EVENT_ID, event("payment.created", Map.of("paymentIntentId", INTENT)))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAPayloadMissingAFieldTheSchemaPromises() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", INTENT);
        payload.put("amountMinor", 100);
        payload.put("currency", "INR");
        payload.put("status", "SUCCEEDED");

        assertThatThrownBy(() -> translator.translate(EVENT_ID, event("payment.succeeded", payload)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capturedAmountMinor");
    }

    @Test
    void publishesExactlyTheFourTypesThePlanNames() {
        assertThat(WebhookPayloadTranslator.PUBLISHED_TYPES).containsExactlyInAnyOrder(
            "payment.succeeded", "payment.failed", "refund.succeeded", "order.paid"
        );
    }

    private static OutboxEvent event(String eventType, Map<String, Object> payload) {
        return new OutboxEvent(
            EventId.generate(), MERCHANT, "PAYMENT_INTENT", INTENT, eventType, 1, payload, RECORDED
        );
    }
}
