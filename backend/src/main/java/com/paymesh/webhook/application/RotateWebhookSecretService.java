package com.paymesh.webhook.application;

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
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RotateWebhookSecretService(
        WebhookEndpointRepository endpoints,
        byte[] masterKey,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.endpoints = endpoints;
        this.masterKey = masterKey.clone();
        this.transactions = transactions;
        this.clock = clock;
    }

    public RegisteredWebhookEndpoint rotate(
        MerchantId merchantId, EndpointId endpointId, int fromVersion
    ) {
        WebhookEndpoint rotated = transactions.execute(status -> {
            WebhookEndpoint endpoint = endpoints.findByEndpointId(merchantId, endpointId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(endpointId));

            WebhookEndpoint next = endpoint.rotateSecret(fromVersion, Instant.now(clock));

            // Unchanged means the retry case above. Writing it anyway would bump the optimistic
            // version and turn an idempotent retry into a lost update somewhere else.
            return next == endpoint ? endpoint : endpoints.save(next);
        });

        return new RegisteredWebhookEndpoint(
            rotated,
            WebhookSecrets.derive(masterKey, rotated.endpointId(), rotated.secretVersion())
        );
    }
}
