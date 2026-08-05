package com.paymesh.webhook.infrastructure.persistence.jpa;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookDeliveryRepository;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed WebhookDeliveryRepository. */
public final class JpaWebhookDeliveryRepository implements WebhookDeliveryRepository {

    private final SpringDataWebhookDeliveryRepository deliveries;

    public JpaWebhookDeliveryRepository(SpringDataWebhookDeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        return WebhookJpaMapper.toDomain(
            deliveries.saveAndFlush(WebhookJpaMapper.toEntity(delivery))
        );
    }

    /**
     * Check-then-insert, and the check is not the guard.
     *
     * <p>{@code uq_webhook_deliveries_event_endpoint} is. This runs inside the event dispatcher's
     * transaction, which the inbox has already serialized per (consumer, event), so the two callers
     * that could race here cannot both be running. If that ever stops being true the unique index
     * throws, the handler rolls back, and the retry finds the row -- which is the same outcome, one
     * pass later.
     */
    @Override
    public boolean saveIfAbsent(WebhookDelivery delivery) {
        boolean present = deliveries.existsByWebhookEventIdAndEndpointId(
            delivery.webhookEventId().value(), delivery.endpointId().value()
        );

        if (present) {
            return false;
        }

        save(delivery);

        return true;
    }

    @Override
    public Optional<WebhookDelivery> findByDeliveryId(
        MerchantId merchantId, WebhookDeliveryId deliveryId
    ) {
        return deliveries.findByMerchantIdAndDeliveryId(merchantId.value(), deliveryId.value())
            .map(WebhookJpaMapper::toDomain);
    }

    @Override
    public List<WebhookDelivery> findByEndpoint(
        MerchantId merchantId, EndpointId endpointId, int limit
    ) {
        return deliveries.findByMerchantIdAndEndpointIdOrderByCreatedAtDesc(
                merchantId.value(), endpointId.value(), Limit.of(limit)
            )
            .stream()
            .map(WebhookJpaMapper::toDomain)
            .toList();
    }

    /** Ids only, so nothing is mapped outside the per-delivery transaction. See the port's javadoc. */
    @Override
    public List<WebhookDeliveryId> findDue(Instant now, int limit) {
        return deliveries.findDueIds(now, PageRequest.ofSize(limit)).stream()
            .map(WebhookDeliveryId::from)
            .toList();
    }

    @Override
    public Optional<WebhookDelivery> claim(WebhookDeliveryId deliveryId) {
        return deliveries.findPendingForUpdate(deliveryId.value()).map(WebhookJpaMapper::toDomain);
    }
}
