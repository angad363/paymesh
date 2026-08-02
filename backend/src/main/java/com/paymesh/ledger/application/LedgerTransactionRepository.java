package com.paymesh.ledger.application;

import com.paymesh.ledger.domain.LedgerTransaction;

import java.util.Optional;

public interface LedgerTransactionRepository {

    /**
     * Writes the header and every entry.
     *
     * @throws LedgerTransactionAlreadyPostedException when {@code idempotencyKey} is already taken.
     *     Detected by {@code uq_ledger_transactions_idempotency}, not by a pre-read -- two
     *     concurrent deliveries of one capture both find nothing and both insert, and the unique
     *     index is what picks the winner.
     */
    LedgerTransaction post(LedgerTransaction transaction);

    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
}
