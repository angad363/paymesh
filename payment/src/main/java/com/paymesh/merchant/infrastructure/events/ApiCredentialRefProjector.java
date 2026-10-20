package com.paymesh.merchant.infrastructure.events;

import com.paymesh.merchant.infrastructure.persistence.ApiCredentialRefStore;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.Map;

/**
 * Feeds {@code api_credential_ref} from {@code merchant.api_credential.issued} / {@code .revoked}
 * (ADR-048 section 4). Two beans, one class, differing only in the event type, like
 * {@code MerchantRefProjector}. Consumer name {@code payment-api-credential}, STABLE: renaming it
 * re-opens the whole backlog to this consumer.
 * <p>
 * No transaction of its own (the dispatcher's holds the inbox row), idempotent through the store's
 * upsert, and a payload missing a required field throws so the relay retries and eventually
 * dead-letters it rather than projecting a half-credential.
 */
public final class ApiCredentialRefProjector implements EventHandler {

    private static final String CONSUMER_NAME = "payment-api-credential";

    private final String eventType;
    private final ApiCredentialRefStore store;

    public ApiCredentialRefProjector(String eventType, ApiCredentialRefStore store) {
        this.eventType = eventType;
        this.store = store;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(OutboxEvent event) {
        Map<String, Object> payload = event.payload();
        Object revokedAt = payload.get("revokedAt");

        if (revokedAt == null && eventType.endsWith(".revoked")) {
            throw new IllegalStateException(
                "Credential event " + event.eventType() + " (" + event.eventId().value()
                    + ") carries no revokedAt; cannot project"
            );
        }

        store.upsert(
            required(payload, "apiCredentialId", event),
            required(payload, "merchantId", event),
            required(payload, "publicPrefix", event),
            required(payload, "secretHash", event),
            required(payload, "role", event),
            revokedAt == null ? null : Instant.parse(revokedAt.toString())
        );
    }

    private static String required(Map<String, Object> payload, String field, OutboxEvent event) {
        Object value = payload.get(field);

        if (value == null) {
            throw new IllegalStateException(
                "Credential event " + event.eventType() + " (" + event.eventId().value()
                    + ") carries no " + field + "; cannot project"
            );
        }

        return value.toString();
    }
}
