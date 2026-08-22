package com.paymesh.webhook.application;

import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.audit.AuditEntry;
import com.paymesh.shared.audit.AuditRecorder;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookEndpointNotFoundException;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookSecrets;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

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
 */
public final class RotateWebhookSecretService {

    private final WebhookEndpointRepository endpoints;
    private final byte[] masterKey;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RotateWebhookSecretService(
        WebhookEndpointRepository endpoints,
        byte[] masterKey,
        AuditRecorder auditRecorder,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.endpoints = endpoints;
        this.masterKey = masterKey.clone();
        this.auditRecorder = auditRecorder;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param operatorId the {@code usr_} rotating the secret, for the audit log. The secret itself
     *     is never audited -- only the version bump, and even that as hashed before/after -- because
     *     a signing secret in the audit log would defeat the point of deriving it and never storing
     *     it (ADR-028, ADR-035).
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

            // Only a REAL rotation is audited, inside this transaction, so the audit row and the
            // bump commit together. The idempotent retry above records nothing -- there was no
            // second rotation to log.
            auditRecorder.record(
                AuditEntry.builder("webhook.secret_rotated", ActorType.USER)
                    .actorId(operatorId)
                    .merchant(merchantId)
                    .resource("webhook_endpoint", endpointId.value())
                    .changing(
                        "v" + endpoint.secretVersion(), "v" + saved.secretVersion()
                    )
                    .build()
            );

            return saved;
        });

        return new RegisteredWebhookEndpoint(
            rotated,
            WebhookSecrets.derive(masterKey, rotated.endpointId(), rotated.secretVersion())
        );
    }
}
