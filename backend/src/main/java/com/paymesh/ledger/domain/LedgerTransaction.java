package com.paymesh.ledger.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.List;

/**
 * A journal: a header plus its immutable entries, balanced by construction.
 *
 * <h2>THERE IS NO WAY TO HOLD AN UNBALANCED ONE</h2>
 *
 * The constructor throws, so an instance of this type is proof that its debits equal its credits.
 * Nothing downstream re-checks, and nothing downstream has to -- a service cannot forget a
 * validation it has no opportunity to skip.
 * <p>
 * That is the readable half of the guarantee. The enforced half is
 * {@code tr_ledger_entries_balanced}, a deferred constraint trigger that re-checks the same sum at
 * COMMIT with the application entirely out of the path (V15). This class exists so a caller gets a
 * sentence instead of a constraint violation; the trigger exists because this class can be
 * refactored around and the trigger cannot.
 *
 * <h2>Why there is no {@code addEntry}</h2>
 *
 * A journal is assembled and then posted, never amended -- SDD 15.6's fifth invariant, and the
 * database enforces it with {@code tr_ledger_entries_immutable}. A mutable builder would let a
 * caller hold a half-built journal across a transaction boundary and post whatever it had. The
 * entries arrive together or the object does not exist.
 */
public record LedgerTransaction(
    LedgerTransactionId ledgerTransactionId,
    MerchantId merchantId,
    String transactionType,
    String referenceType,
    String referenceId,
    String currency,
    String idempotencyKey,
    List<LedgerEntry> entries,
    Instant occurredAt,
    Instant createdAt
) {

    /** SDD 15.4's transaction type for a captured payment. */
    public static final String PAYMENT_CAPTURED = "PAYMENT_CAPTURED";

    /** What a {@link #PAYMENT_CAPTURED} journal points back at. */
    public static final String REFERENCE_PAYMENT_INTENT = "PAYMENT_INTENT";

    public LedgerTransaction {
        if (ledgerTransactionId == null) {
            throw new IllegalArgumentException("Ledger transaction identifier is required");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("A ledger transaction must name a merchant");
        }

        transactionType = requireText(transactionType, "Ledger transaction type");
        referenceType = requireText(referenceType, "Ledger transaction reference type");
        referenceId = requireText(referenceId, "Ledger transaction reference identifier");
        idempotencyKey = requireText(idempotencyKey, "Ledger transaction idempotency key");
        currency = requireCurrency(currency);

        if (occurredAt == null) {
            throw new IllegalArgumentException("Ledger transaction occurrence instant is required");
        }

        if (createdAt == null) {
            throw new IllegalArgumentException("Ledger transaction creation instant is required");
        }

        entries = List.copyOf(requireBalanced(entries));
    }

    /**
     * SDD 15.2's posting, minus the fee split.
     *
     * <h2>TWO ENTRIES, BOTH GROSS, AND IT STILL BALANCES</h2>
     *
     * The SDD's worked example is three entries: provider clearing debited the gross, merchant
     * pending credited the net, platform fee revenue credited the difference. There is no fee
     * schedule anywhere in this codebase -- no rate, no rounding rule, no per-merchant pricing, no
     * effective dates -- so the third entry could only be computed from a number invented here.
     * Inventing it would put a made-up rate into immutable rows that no later correction can edit.
     * <p>
     * Dropping the fee does not weaken the invariant, only the detail: debits equal credits either
     * way. When a fee schedule exists, it adds a third entry to new postings and leaves every
     * existing one alone -- which is exactly what an append-only ledger is for. ADR-018 section 4.
     *
     * <h2>What the direction means</h2>
     *
     * The provider owes PayMesh the captured amount, so PayMesh's receivable from them grows: a
     * DEBIT to an asset. PayMesh in turn owes the merchant, so that liability grows: a CREDIT. One
     * capture, one obligation passed along, two entries that cancel.
     */
    public static LedgerTransaction paymentCaptured(
        MerchantId merchantId,
        String paymentIntentId,
        LedgerAccountId providerClearingAccountId,
        LedgerAccountId merchantPendingAccountId,
        long capturedAmountMinor,
        String currency,
        Instant occurredAt,
        Instant createdAt
    ) {
        return new LedgerTransaction(
            LedgerTransactionId.generate(),
            merchantId,
            PAYMENT_CAPTURED,
            REFERENCE_PAYMENT_INTENT,
            paymentIntentId,
            currency,
            paymentCapturedIdempotencyKey(paymentIntentId),
            List.of(
                LedgerEntry.debit(providerClearingAccountId, capturedAmountMinor),
                LedgerEntry.credit(merchantPendingAccountId, capturedAmountMinor)
            ),
            occurredAt,
            createdAt
        );
    }

    /**
     * SDD 15.4's key shape, {@code payment-captured:pi_<uuid>}.
     *
     * <h2>KEYED ON THE PAYMENT, NOT ON THE EVENT, AND THE DIFFERENCE MATTERS</h2>
     *
     * The inbox ({@code processed_events}, V14) already stops one event being applied twice by one
     * consumer. Keying this on the event id would duplicate that and protect nothing extra. Keying
     * it on the PAYMENT catches what the inbox structurally cannot see: the same capture arriving
     * as a different event -- a replayed backlog after a consumer is renamed, a manual re-run, or a
     * second emitter added later. Two events describing one capture collide here, and the second is
     * refused by {@code uq_ledger_transactions_idempotency} rather than posted.
     * <p>
     * Exposed as a method because a test pins the shape. That test is what will force a decision if
     * partial captures ever become repeatable for one intent -- the key would then need the capture
     * sequence, and the alternative is silently refusing a genuine second capture.
     */
    public static String paymentCapturedIdempotencyKey(String paymentIntentId) {
        return "payment-captured:" + paymentIntentId;
    }

    /** Total of the DEBIT entries, in minor units. */
    public long totalDebitsMinor() {
        return total(Direction.DEBIT);
    }

    /** Total of the CREDIT entries, in minor units. */
    public long totalCreditsMinor() {
        return total(Direction.CREDIT);
    }

    private long total(Direction direction) {
        return entries.stream()
            .filter(entry -> entry.direction() == direction)
            .mapToLong(LedgerEntry::amountMinor)
            .sum();
    }

    private static List<LedgerEntry> requireBalanced(List<LedgerEntry> entries) {
        if (entries == null || entries.size() < 2) {
            // Fewer than two entries cannot balance with positive amounts, and an EMPTY list would
            // otherwise pass the sum comparison below as 0 == 0. The trigger in V15 makes the same
            // check for the same reason.
            throw new IllegalArgumentException(
                "A ledger transaction needs at least two entries, got "
                    + (entries == null ? 0 : entries.size())
            );
        }

        long debits = 0;
        long credits = 0;

        for (LedgerEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("A ledger transaction cannot hold a null entry");
            }

            if (entry.direction() == Direction.DEBIT) {
                debits += entry.amountMinor();
            } else {
                credits += entry.amountMinor();
            }
        }

        if (debits != credits) {
            throw new UnbalancedTransactionException(debits, credits);
        }

        return entries;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }

        return value.strip();
    }

    private static String requireCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }

        String normalised = currency.strip().toUpperCase();

        if (!normalised.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be three letters, got " + currency);
        }

        return normalised;
    }
}
