package com.paymesh.identity.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSecurityEventRepository
    extends JpaRepository<SecurityEventJpaEntity, String> {
}
