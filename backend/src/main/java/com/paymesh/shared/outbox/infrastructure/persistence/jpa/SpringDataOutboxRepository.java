package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the outbox_events table.
 * <p>
 * Write-only for now, and the inherited read methods have no caller: the relay that will need
 * "oldest unpublished first" does not exist, and a query written before it would be guessing at
 * its claim strategy.
 */
public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, String> {
}
