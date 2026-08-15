package com.paymesh.simulator.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataSimulatedPayoutRepository
    extends JpaRepository<SimulatedPayoutJpaEntity, String> {

    Optional<SimulatedPayoutJpaEntity> findByExternalReference(String externalReference);
}
