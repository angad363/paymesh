package com.paymesh.ledger.infrastructure.persistence.jpa;

import com.paymesh.ledger.application.LedgerTransactionAlreadyPostedException;
import org.springframework.data.domain.Limit;
import com.paymesh.ledger.application.LedgerTransactionRepository;
import com.paymesh.ledger.application.ReleasableCapture;
import com.paymesh.ledger.domain.LedgerEntry;
import com.paymesh.ledger.domain.LedgerTransaction;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JpaLedgerTransactionRepository implements LedgerTransactionRepository {

    private final SpringDataLedgerTransactionRepository transactions;
    private final SpringDataLedgerEntryRepository entries;

    public JpaLedgerTransactionRepository(
        SpringDataLedgerTransactionRepository transactions,
        SpringDataLedgerEntryRepository entries
    ) {
        this.transactions = transactions;
        this.entries = entries;
    }

    /**
     * Header first, flushed, then the entries.
     *
     * <h2>THE ORDER IS NOT INCIDENTAL</h2>
     *
     * {@code fk_ledger_entries_transaction} points at {@code (ledger_transaction_id, currency)} on
     * the header, so the header row has to exist before any entry can reference it. The
     * {@code saveAndFlush} makes that explicit rather than leaving it to Hibernate's flush ordering
     * -- which would in fact get it right, but only because it happens to sort by insert
     * dependency, and this is not a thing to leave to inference on the money path.
     *
     * <h2>What this method does NOT prove</h2>
     *
     * That the journal balances. {@code tr_ledger_entries_balanced} is DEFERRED, so it does not run
     * at this flush; it runs when the surrounding transaction commits, which happens after this
     * method has returned and after the dispatcher has committed the inbox row alongside it. That
     * is the correct moment -- a journal is balanced only once all its entries exist -- but it does
     * mean an unbalanced posting surfaces as an exception from the COMMIT rather than from here,
     * and a test that rolls back will never see it fire at all.
     * <p>
     * {@link LedgerTransaction} refuses to construct an unbalanced journal in the first place, so
     * reaching the trigger at all means something bypassed the domain.
     */
    @Override
    public LedgerTransaction post(LedgerTransaction transaction) {
        try {
            transactions.saveAndFlush(LedgerJpaMapper.toEntity(transaction));
        } catch (DataIntegrityViolationException exception) {
            // The idempotency key is the only unique constraint on this table that a caller can
            // collide with -- the primary key is a fresh UUID. Named rather than rethrown so the
            // service layer can talk about "already posted" instead of about a constraint.
            //
            // NOTE that the transaction is now aborted; nothing may read from it. The service does
            // not try to. See PostPaymentCapturedService for why that is deliberate and how the
            // redelivery recovers.
            throw new LedgerTransactionAlreadyPostedException(transaction.idempotencyKey());
        }

        for (LedgerEntry entry : transaction.entries()) {
            entries.save(LedgerJpaMapper.toEntity(transaction, entry));
        }

        entries.flush();

        return transaction;
    }

    @Override
    public Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey) {
        return transactions.findByIdempotencyKey(idempotencyKey).map(header -> {
            List<LedgerEntryJpaEntity> lines =
                entries.findByLedgerTransactionIdOrderByLedgerEntryIdAsc(header.ledgerTransactionId());

            return LedgerJpaMapper.toDomain(header, lines);
        });
    }

    @Override
    public List<ReleasableCapture> findUnreleasedCaptures(int limit) {
        return transactions.findUnreleasedCaptures(Limit.of(limit)).stream()
            // RAW, unparsed -- the release pass builds its MerchantId inside its own try.
            .map(row -> new ReleasableCapture(
                (String) row[0], (String) row[1], (String) row[2], (Instant) row[3]
            ))
            .toList();
    }

    @Override
    public int lockPaymentJournals(String paymentIntentId) {
        return transactions.lockPaymentJournals(paymentIntentId).size();
    }
}
