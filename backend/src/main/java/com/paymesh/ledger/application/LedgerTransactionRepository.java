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

    /**
     * Captures with no {@code funds-released} journal yet, oldest first, bounded.
     * <p>
     * RAW COLUMNS, not aggregates: the release pass parses them inside its per-item try, so one
     * unreadable row costs one payment rather than the run (open item 2).
     * <p>
     * The anti-join means already-released captures are filtered rather than returned, but they are
     * still visited as the table grows. That is the known ceiling here; a partial index on
     * unreleased captures is the upgrade when it starts to matter, and it is a measurement rather
     * than a guess.
     */
    java.util.List<ReleasableCapture> findUnreleasedCaptures(int limit);
}
