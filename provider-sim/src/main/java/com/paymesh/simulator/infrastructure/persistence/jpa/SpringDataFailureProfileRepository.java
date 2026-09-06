package com.paymesh.simulator.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data's half of the failure-profile adapter. One row, seeded by V13. */
public interface SpringDataFailureProfileRepository
    extends JpaRepository<FailureProfileJpaEntity, String> {
}
