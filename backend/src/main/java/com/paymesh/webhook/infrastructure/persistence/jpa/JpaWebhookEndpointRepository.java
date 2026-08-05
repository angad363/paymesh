package com.paymesh.webhook.infrastructure.persistence.jpa;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookEndpointAlreadyExistsException;
import com.paymesh.webhook.application.WebhookEndpointRepository;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.EndpointStatus;
import com.paymesh.webhook.domain.WebhookEndpoint;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed WebhookEndpointRepository. Everything JPA stops here. */
public final class JpaWebhookEndpointRepository implements WebhookEndpointRepository {

    private final SpringDataWebhookEndpointRepository endpoints;

    public JpaWebhookEndpointRepository(SpringDataWebhookEndpointRepository endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * {@code saveAndFlush}, so a violation surfaces here rather than at an arbitrary later flush.
     *
     * <p>{@code uq_webhook_endpoints_merchant_url} is the ONLY guard on a duplicate URL, and there
     * is deliberately no application pre-check beside it. A pre-check would add a query, a race and
     * a second place to be wrong, and would still need this catch block underneath it -- the
     * constraint is what makes the rule true, and this translation is what makes it a readable 409
     * rather than a 500. Two endpoints at one URL would fan out twice to the same place.
     */
    @Override
    public WebhookEndpoint save(WebhookEndpoint endpoint) {
        try {
            return WebhookJpaMapper.toDomain(
                endpoints.saveAndFlush(WebhookJpaMapper.toEntity(endpoint))
            );
        } catch (DataIntegrityViolationException exception) {
            if (violates(exception, "uq_webhook_endpoints_merchant_url")) {
                throw new WebhookEndpointAlreadyExistsException(endpoint.url());
            }

            throw exception;
        }
    }

    /**
     * By constraint NAME, not by message text. Any other violation -- a missing merchant tripping
     * {@code fk_webhook_endpoints_merchant}, say -- must not be disguised as a duplicate URL.
     */
    private static boolean violates(DataIntegrityViolationException exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraint.equals(violation.getConstraintName());
            }
        }

        return false;
    }

    @Override
    public Optional<WebhookEndpoint> findByEndpointId(MerchantId merchantId, EndpointId endpointId) {
        return endpoints.findByMerchantIdAndEndpointId(merchantId.value(), endpointId.value())
            .map(WebhookJpaMapper::toDomain);
    }

    @Override
    public List<WebhookEndpoint> findByMerchant(MerchantId merchantId) {
        return endpoints.findByMerchantIdOrderByCreatedAtAsc(merchantId.value()).stream()
            .map(WebhookJpaMapper::toDomain)
            .toList();
    }

    @Override
    public List<WebhookEndpoint> findActiveByMerchant(MerchantId merchantId) {
        return endpoints
            .findByMerchantIdAndStatus(merchantId.value(), EndpointStatus.ACTIVE.name()).stream()
            .map(WebhookJpaMapper::toDomain)
            .toList();
    }

    @Override
    public long countByMerchant(MerchantId merchantId) {
        return endpoints.countByMerchantId(merchantId.value());
    }
}
