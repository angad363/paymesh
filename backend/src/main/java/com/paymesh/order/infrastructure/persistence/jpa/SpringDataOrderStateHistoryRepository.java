package com.paymesh.order.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to order_state_history. Inserts only -- nothing reads a timeline yet, and a
 * finder with no caller would be a guess about what a future endpoint wants.
 */
public interface SpringDataOrderStateHistoryRepository
    extends JpaRepository<OrderStateHistoryJpaEntity, Long> {
}
