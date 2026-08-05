package com.paymesh.webhook.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data access to webhook_endpoints. */
public interface SpringDataWebhookEndpointRepository
    extends JpaRepository<WebhookEndpointJpaEntity, String> {

    Optional<WebhookEndpointJpaEntity> findByMerchantIdAndEndpointId(
        String merchantId, String endpointId
    );

    Optional<WebhookEndpointJpaEntity> findByMerchantIdAndUrl(String merchantId, String url);

    List<WebhookEndpointJpaEntity> findByMerchantIdOrderByCreatedAtAsc(String merchantId);

    /**
     * The fan-out query, run inside the event dispatcher's transaction.
     *
     * <p>Filtered on status here and on {@code subscriptions} in Java, because a jsonb array cannot
     * be a JPA predicate. That is why {@code MAX_ENDPOINTS_PER_MERCHANT} exists (ADR-028 §5): the
     * work this does inside the money path has to have a ceiling.
     */
    List<WebhookEndpointJpaEntity> findByMerchantIdAndStatus(String merchantId, String status);

    long countByMerchantId(String merchantId);
}
