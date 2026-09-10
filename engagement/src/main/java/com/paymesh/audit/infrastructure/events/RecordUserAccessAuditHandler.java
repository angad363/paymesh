package com.paymesh.audit.infrastructure.events;

import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.audit.AuditEntry;
import com.paymesh.shared.audit.AuditRecorder;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Turns Identity's {@code identity.user_access.audited} event back into an {@code audit_events} row
 * (ADR-043 section 5, generalizing the mechanism ADR-042 section 4 built for webhook's secret
 * rotation).
 *
 * <h2>THE PLATFORM-SCOPED CASE: NO MERCHANT AT ALL, NOT JUST NONE ON THIS ROW</h2>
 *
 * {@code ManageUserAccessService.audit(...)} was called with a null {@code merchantId} for every
 * platform-scoped action (suspend, reactivate, close, platform-role grant/revoke) even before
 * extraction -- {@code AuditEntry.merchantId} has always been nullable for exactly this reason
 * (V36's own {@code audit_events.merchant_id}). Carrying that same fact across the outbox meant
 * {@link OutboxEvent#merchantId()} itself had to become nullable (ADR-043; see the monolith's V40 and
 * this module's V2), rather than inventing a sentinel tenant to satisfy a NOT NULL that used to be
 * true of every event so far. A merchant-scoped grant or revoke (access_granted/access_revoked)
 * still carries a real {@link OutboxEvent#merchantId()}; this handler passes it through unchanged
 * either way, null or not -- {@link AuditEntry.Builder#merchant} already accepts null.
 *
 * <h2>THE RECONSTRUCTED ENTRY IS BYTE-FOR-BYTE WHAT THE IN-PROCESS CALL BUILT</h2>
 *
 * The payload carries the same fields {@code ManageUserAccessService}'s private {@code audit(...)}
 * helper always built: the dynamic action string ({@code user.suspended},
 * {@code user.platform_admin_granted}, {@code user.access_granted}, ...), the operator, the target
 * user as the resource, and an optional reason (the role granted, when there is one). No
 * before/after and no IP, exactly as the in-process call never carried them either.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> {@link AuditRecorder#record} runs inside the dispatcher's
 *       transaction, so the audit row and the inbox row commit together.</li>
 *   <li><b>Idempotent anyway.</b> A redelivered event reaches here only if the inbox has not already
 *       claimed it; this handler performs a plain append.</li>
 *   <li><b>Throws to retry.</b> A payload missing a required field throws, leaving the inbox
 *       unclaimed so the relay retries and, eventually, dead-letters it (ADR-025).</li>
 * </ol>
 */
public final class RecordUserAccessAuditHandler implements EventHandler {

    private static final String EVENT_TYPE = "identity.user_access.audited";
    private static final String CONSUMER_NAME = "audit." + EVENT_TYPE;

    private final AuditRecorder auditRecorder;

    public RecordUserAccessAuditHandler(AuditRecorder auditRecorder) {
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
        String reason = optionalString(event, "reason");

        auditRecorder.record(
            AuditEntry.builder(action, ActorType.USER)
                .actorId(actorId)
                .merchant(event.merchantId())
                .resource(resourceType, resourceId)
                .reason(reason)
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
