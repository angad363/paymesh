package com.paymesh.webhook.application;

import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookEndpointNotFoundException;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookSecrets;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bumps the signing version and hands back the new secret, once.
 *
 * <h2>ROTATION IS AN INTEGER INCREMENT, WHICH IS THE POINT OF THE WHOLE DESIGN</h2>
 *
 * There is no ciphertext to re-encrypt and no key map to update. The old version keeps signing for
 * {@code WebhookEndpoint.ROTATION_OVERLAP}, so a merchant who has not yet deployed their new
 * verifier still receives deliveries they can check.
 *
 * <h2>THE CALLER NAMES THE VERSION IT IS ROTATING FROM</h2>
 *
 * Not a formality: it makes a retried rotation idempotent. Asked to rotate from a version already
 * spent, the aggregate returns unchanged and this re-derives the same secret rather than bumping
 * again -- so a client that lost the response can safely ask twice. That is what lets this route
 * stay off {@code IdempotencyFilter}, which would otherwise persist the secret in a response body.
 *
 * <h2>AUDITING CROSSED A PROCESS BOUNDARY (ADR-042 section 4)</h2>
 *
 * This used to call {@code AuditRecorder.record(...)} in-process, inside this same transaction --
 * {@code webhook_svc} cannot write {@code audit_events} any more; that table left with Audit into
 * the {@code engagement} schema (ADR-038). The replacement keeps the atomicity guarantee, just moved
 * one layer down: a {@code webhook.secret_rotated.audited} event goes to THIS module's own outbox,
 * in the SAME transaction as the rotation, and the monolith's audit capability consumes it and
 * writes the audit row. Same before/after, same actor -- reconstructed from the event payload rather
 * than passed as a method call. This is PR 8's mechanism (ADR-043), pulled forward for this one
 * audited action.
 */
public final class RotateWebhookSecretService {

    private static final int SECRET_ROTATED_AUDITED_VERSION = 1;

    private final WebhookEndpointRepository endpoints;
    private final byte[] masterKey;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RotateWebhookSecretService(
        WebhookEndpointRepository endpoints,
        byte[] masterKey,
        OutboxWriter outboxWriter,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.endpoints = endpoints;
        this.masterKey = masterKey.clone();
        this.outboxWriter = outboxWriter;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param operatorId the {@code usr_} rotating the secret, for the audit log. The secret itself
     *     is never audited -- only the version bump, and even that as before/after strings the
     *     audit consumer hashes on arrival -- because a signing secret in the audit log would defeat
     *     the point of deriving it and never storing it (ADR-028, ADR-035).
     */
    public RegisteredWebhookEndpoint rotate(
        MerchantId merchantId, EndpointId endpointId, int fromVersion, String operatorId
    ) {
        WebhookEndpoint rotated = transactions.execute(status -> {
            WebhookEndpoint endpoint = endpoints.findByEndpointId(merchantId, endpointId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(endpointId));

            WebhookEndpoint next = endpoint.rotateSecret(fromVersion, Instant.now(clock));

            // Unchanged means the retry case above. Writing it anyway would bump the optimistic
            // version and turn an idempotent retry into a lost update somewhere else.
            if (next == endpoint) {
                return endpoint;
            }

            WebhookEndpoint saved = endpoints.save(next);

            // Only a REAL rotation is audited, appended to THIS outbox inside this same
            // transaction, so the event and the bump commit together. The idempotent retry above
            // appends nothing -- there was no second rotation to log.
            outboxWriter.append(secretRotatedAuditedEvent(merchantId, endpointId, operatorId,
                endpoint.secretVersion(), saved.secretVersion()));

            return saved;
        });

        return new RegisteredWebhookEndpoint(
            rotated,
            WebhookSecrets.derive(masterKey, rotated.endpointId(), rotated.secretVersion())
        );
    }

    private OutboxEvent secretRotatedAuditedEvent(
        MerchantId merchantId, EndpointId endpointId, String operatorId, int fromVersion, int toVersion
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchantId.value());
        payload.put("endpointId", endpointId.value());
        payload.put("operatorId", operatorId);
        // Plaintext version strings, not pre-hashed: the monolith's consumer reconstructs the same
        // AuditEntry the in-process call used to build, and AuditRecorder hashes before/after itself
        // (AuditEntry's own javadoc) -- hashing here too would hash twice and break the audit row.
        payload.put("before", "v" + fromVersion);
        payload.put("after", "v" + toVersion);

        Instant now = Instant.now(clock);

        return new OutboxEvent(
            EventId.generate(),
            merchantId,
            "WEBHOOK_ENDPOINT",
            endpointId.value(),
            "webhook.secret_rotated.audited",
            SECRET_ROTATED_AUDITED_VERSION,
            payload,
            now
        );
    }
}
