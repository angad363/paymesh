package com.paymesh.settlement.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSettlementConfigRepository
    extends JpaRepository<SettlementConfigJpaEntity, String> {
}
