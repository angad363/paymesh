package com.paymesh.customer.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataCustomerStatusHistoryRepository
    extends JpaRepository<CustomerStatusHistoryJpaEntity, Long> {
}
