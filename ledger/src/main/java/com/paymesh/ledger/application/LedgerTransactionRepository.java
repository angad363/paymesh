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

    /**
     * Take the row lock that serializes everything touching one payment's pending position.
     *
     * <h2>WITHOUT THIS, RELEASE AND A REFUND OF THE SAME PAYMENT CAN BOTH READ A WORLD WITHOUT THE
     * OTHER</h2>
     *
     * The release job reads how much of a payment is still pending and posts that amount; the
     * refund reversal reads whether the payment has been released and picks the account to debit
     * from the answer. Under {@code READ COMMITTED} each reads its own snapshot, so a reversal
     * committing between the job's read and its post makes the job release the GROSS: pending goes
     * negative by the refund, available goes too high by the same, and available is the figure
     * Settlement pays out against. The totals stay right, which is exactly why nothing else
     * notices.
     * <p>
     * ADR-019 §4.1's lesson applied a second time: <b>a deferred constraint trigger cannot fix
     * this</b>, because it runs on the snapshot of the statement that queued it and both writers
     * still pass. The lock is the mechanism; the arithmetic is only the check.
     *
     * @return how many journals this payment already has. Zero means the capture has not posted,
     *     which for a caller reacting to a refund or a release means there is nothing to serialize
     *     against yet.
     */
    int lockPaymentJournals(String paymentIntentId);
}
