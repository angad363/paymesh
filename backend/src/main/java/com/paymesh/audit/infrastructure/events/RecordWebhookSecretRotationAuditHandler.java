package com.paymesh.audit.infrastructure.events;

import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.audit.AuditEntry;
import com.paymesh.shared.audit.AuditRecorder;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;

/**
 * Turns webhook's {@code webhook.secret_rotated.audited} event back into an {@code audit_events} row
 * (ADR-042 section 4, PR 8's mechanism arriving one action early -- ADR-043).
 *
 * <h2>WHY THIS EXISTS: THE IN-PROCESS AUDIT CALL COULD NOT SURVIVE EXTRACTION</h2>
 *
 * Before webhook left the monolith, {@code RotateWebhookSecretService} called
 * {@link AuditRecorder#record(AuditEntry)} in-process, inside the rotation's own transaction. Once
 * {@code webhook_svc} could no longer write {@code audit_events} (the table moved to the
 * {@code engagement} schema, ADR-038), that call became impossible. Webhook now appends
 * {@code webhook.secret_rotated.audited} to ITS OWN outbox, in the same transaction as the rotation
 * (the atomicity guarantee moved from "same DB transaction" to "same outbox transaction," which is
 * strictly stronger than a synchronous cross-service call that could fail after the rotation already
 * committed). This handler is the other half: the monolith's existing {@code KafkaEventListener}
 * already consumes every {@code .+-events} topic (ADR-037) and dispatches to every registered
 * {@link EventHandler}, so subscribing needed exactly one new bean, not a new listener.
 *
 * <h2>THE RECONSTRUCTED ENTRY IS BYTE-FOR-BYTE WHAT THE IN-PROCESS CALL BUILT</h2>
 *
 * The payload carries the same fields the old in-process {@code AuditEntry.builder(...)} call did:
 * the actor, the merchant, the resource, and the PLAINTEXT before/after version strings
 * ({@code "v1"}, {@code "v2"}). {@link AuditRecorder#record} hashes them on the way in, exactly as it
 * always has -- hashing them again here, before this call, would hash twice and produce a different
 * audit row than the in-process call used to write.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> {@link AuditRecorder#record} runs inside the dispatcher's
 *       transaction, so the audit row and the inbox row commit together.</li>
 *   <li><b>Idempotent anyway.</b> A redelivered event reaches here only if the inbox has not already
 *       claimed it (the dispatcher's own dedup); this handler itself performs a plain append, which
 *       is the same "one event, one audit row" shape every other audited action already has.</li>
 *   <li><b>Throws to retry.</b> A payload missing a required field throws, leaving the inbox unclaimed
 *       so the relay retries -- and, since the payload stays malformed, eventually dead-letters it
 *       (ADR-025), the correct end for an event that cannot be turned into an audit row.</li>
 * </ol>
 */
public final class RecordWebhookSecretRotationAuditHandler implements EventHandler {

    private static final String EVENT_TYPE = "webhook.secret_rotated.audited";
    private static final String CONSUMER_NAME = "audit." + EVENT_TYPE;

    private final AuditRecorder auditRecorder;

    public RecordWebhookSecretRotationAuditHandler(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        String endpointId = requireString(event, "endpointId");
        String operatorId = requireString(event, "operatorId");
        String before = requireString(event, "before");
        String after = requireString(event, "after");

        auditRecorder.record(
            AuditEntry.builder("webhook.secret_rotated", ActorType.USER)
                .actorId(operatorId)
                .merchant(MerchantId.from(event.merchantId().value()))
                .resource("webhook_endpoint", endpointId)
                .changing(before, after)
                .build()
        );
    }

    private static String requireString(OutboxEvent event, String field) {
        Object value = event.payload().get(field);

        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                EVENT_TYPE + " event (" + event.eventId().value() + ") carries no " + field
                    + "; cannot record the audit row"
            );
        }

        return text;
    }
}
