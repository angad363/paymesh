package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookEndpointExceptions.UnpublishedEventTypeException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookEndpointNotFoundException;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.EndpointStatus;
import com.paymesh.webhook.domain.WebhookEndpoint;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Changes what an endpoint subscribes to, or whether it is running.
 *
 * <p>Re-enabling clears the failure streak, which the aggregate decides rather than this service: a
 * merchant who fixed their endpoint and turned it back on should not be one dead delivery from being
 * disabled again.
 */
public final class UpdateWebhookEndpointService {

    private final WebhookEndpointRepository endpoints;
    private final Set<String> publishedEventTypes;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public UpdateWebhookEndpointService(
        WebhookEndpointRepository endpoints,
        Set<String> publishedEventTypes,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.endpoints = endpoints;
        this.publishedEventTypes = Set.copyOf(publishedEventTypes);
        this.transactions = transactions;
        this.clock = clock;
    }

    public WebhookEndpoint update(
        MerchantId merchantId, EndpointId endpointId, UpdateWebhookEndpointCommand command
    ) {
        requirePublished(command.subscriptions());

        EndpointStatus requestedStatus = parseStatus(command.status());

        return transactions.execute(status -> {
            Instant now = Instant.now(clock);

            WebhookEndpoint endpoint = endpoints.findByEndpointId(merchantId, endpointId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(endpointId));

            WebhookEndpoint updated = endpoint;

            if (command.subscriptions() != null) {
                updated = updated.resubscribe(command.subscriptions(), now);
            }

            if (requestedStatus == EndpointStatus.ACTIVE) {
                updated = updated.enable(now);
            } else if (requestedStatus == EndpointStatus.DISABLED) {
                updated = updated.disable(now);
            }

            // A PATCH that changed nothing writes nothing. Saving anyway would bump the optimistic
            // version and could fail a concurrent rotation for no reason at all.
            return updated == endpoint ? endpoint : endpoints.save(updated);
        });
    }

    private static EndpointStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        try {
            return EndpointStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                "status must be ACTIVE or DISABLED, got: " + status, unknown
            );
        }
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
