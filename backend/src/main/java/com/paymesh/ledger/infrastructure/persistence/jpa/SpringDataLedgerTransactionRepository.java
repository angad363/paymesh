package com.paymesh.ledger.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataLedgerTransactionRepository
    extends JpaRepository<LedgerTransactionJpaEntity, String> {

    Optional<LedgerTransactionJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
