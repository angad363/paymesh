package com.paymesh.payment.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the provider_callbacks table.
 * <p>
 * No declared methods: the adapter only ever inserts. Reconciliation will want to read these rows
 * and does not exist, and a query with no caller would be a guess about what it wants.
 */
public interface SpringDataProviderCallbackRepository
    extends JpaRepository<ProviderCallbackJpaEntity, ProviderCallbackJpaId> {
}
