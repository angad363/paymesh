package com.paymesh.audit.infrastructure.events;

import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.audit.AuditEntry;
import com.paymesh.shared.audit.AuditRecorder;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Turns Merchant's {@code merchant.status_changed.audited} event back into an {@code audit_events}
 * row (ADR-043 section 5, generalizing the mechanism ADR-042 section 4 built for webhook's secret
 * rotation).
 *
 * <h2>WHY THIS EXISTS: THE IN-PROCESS AUDIT CALL COULD NOT SURVIVE EXTRACTION</h2>
 *
 * Before Audit left the monolith, {@code ChangeMerchantStatusService} called
 * {@link AuditRecorder#record(AuditEntry)} in-process, inside the same transaction as the status
 * change. Once Audit moved to its own deployable and {@code audit_events} left the monolith's
 * reach entirely, that call became impossible in either direction: the monolith cannot write
 * {@code engagement}'s tables, and {@code engagement_svc} cannot write the monolith's. Merchant now
 * appends {@code merchant.status_changed.audited} to ITS OWN outbox, in the same transaction as the
 * status change (the atomicity guarantee moves from "same DB transaction" to "same outbox
 * transaction," strictly stronger than a synchronous cross-service call that could fail after the
 * change already committed). This handler is the other half: this module's {@code
 * KafkaEventListener} already consumes every {@code .+-events} topic (ADR-037) and dispatches to
 * every registered {@link EventHandler}, so subscribing needed exactly one new bean.
 *
 * <h2>THE RECONSTRUCTED ENTRY IS BYTE-FOR-BYTE WHAT THE IN-PROCESS CALL BUILT</h2>
 *
 * The payload carries the same fields the old in-process {@code AuditEntry.builder(...)} call did:
 * the full action string ({@code merchant.activated}, {@code merchant.suspended},
 * {@code merchant.closed}), the operator, the resource, and the PLAINTEXT before/after status names.
 * {@link AuditRecorder#record} hashes them on the way in, exactly as it always has -- hashing them
 * again here would hash twice and produce a different audit row than the in-process call used to
 * write. The merchant itself travels as the event's own {@link OutboxEvent#merchantId()}, never
 * null for this event type: every status change is about a real tenant.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> {@link AuditRecorder#record} runs inside the dispatcher's
 *       transaction, so the audit row and the inbox row commit together.</li>
 *   <li><b>Idempotent anyway.</b> A redelivered event reaches here only if the inbox has not already
 *       claimed it; this handler performs a plain append, the same "one event, one audit row" shape
 *       every other audited action has.</li>
 *   <li><b>Throws to retry.</b> A payload missing a required field throws, leaving the inbox
 *       unclaimed so the relay retries and, eventually, dead-letters it (ADR-025) -- the correct end
 *       for an event that cannot be turned into an audit row.</li>
 * </ol>
 */
public final class RecordMerchantStatusChangeAuditHandler implements EventHandler {

    private static final String EVENT_TYPE = "merchant.status_changed.audited";
    private static final String CONSUMER_NAME = "audit." + EVENT_TYPE;

    private final AuditRecorder auditRecorder;

    public RecordMerchantStatusChangeAuditHandler(AuditRecorder auditRecorder) {
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
        String action = requireString(event, "action");
        String actorId = requireString(event, "actorId");
        String resourceType = requireString(event, "resourceType");
        String resourceId = requireString(event, "resourceId");
        String before = requireString(event, "before");
        String after = requireString(event, "after");
        String reason = optionalString(event, "reason");

        if (event.merchantId() == null) {
            throw new IllegalStateException(
                EVENT_TYPE + " event (" + event.eventId().value() + ") carries no merchantId; "
                    + "every merchant status change is about a real tenant"
            );
        }

        auditRecorder.record(
            AuditEntry.builder(action, ActorType.USER)
                .actorId(actorId)
                .merchant(event.merchantId())
                .resource(resourceType, resourceId)
                .reason(reason)
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

    private static String optionalString(OutboxEvent event, String field) {
        Object value = event.payload().get(field);

        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
