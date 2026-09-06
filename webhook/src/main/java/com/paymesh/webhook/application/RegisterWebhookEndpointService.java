package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookEndpointExceptions.TooManyWebhookEndpointsException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.UnpublishedEventTypeException;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookSecrets;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Registers one endpoint and hands back its secret, once. */
public final class RegisterWebhookEndpointService {

    private final WebhookEndpointRepository endpoints;
    private final Set<String> publishedEventTypes;
    private final byte[] masterKey;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * @param publishedEventTypes what PayMesh actually fans out, injected rather than looked up:
     *     the translator that knows them is infrastructure and this is not.
     */
    public RegisterWebhookEndpointService(
        WebhookEndpointRepository endpoints,
        Set<String> publishedEventTypes,
        byte[] masterKey,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.endpoints = endpoints;
        this.publishedEventTypes = Set.copyOf(publishedEventTypes);
        this.masterKey = masterKey.clone();
        this.transactions = transactions;
        this.clock = clock;
    }

    public RegisteredWebhookEndpoint register(MerchantId merchantId, RegisterWebhookEndpointCommand command) {
        requirePublished(command.subscriptions());

        WebhookEndpoint saved = transactions.execute(status -> {
            // A check, not a lock: two concurrent registrations can both pass it and both write.
            // The ceiling it protects is a soft one -- a 21st endpoint costs one more iteration
            // inside the dispatcher's transaction, not a broken invariant -- so a pre-check that
            // gives a readable 422 is worth more here than a lock that serializes registration.
            if (endpoints.countByMerchant(merchantId) >= WebhookEndpoint.MAX_ENDPOINTS_PER_MERCHANT) {
                throw new TooManyWebhookEndpointsException();
            }

            return endpoints.save(WebhookEndpoint.register(
                merchantId.value(), command.url(), command.subscriptions(), Instant.now(clock)
            ));
        });

        return new RegisteredWebhookEndpoint(saved, secretFor(saved));
    }

    private String secretFor(WebhookEndpoint endpoint) {
        return WebhookSecrets.derive(masterKey, endpoint.endpointId(), endpoint.secretVersion());
    }

    private void requirePublished(List<String> subscriptions) {
        if (subscriptions == null) {
            return;
        }

        for (String eventType : subscriptions) {
            if (eventType != null && !publishedEventTypes.contains(eventType.trim())) {
                throw new UnpublishedEventTypeException(eventType, publishedEventTypes);
            }
        }
    }
}
