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

    /** SDD 15.6 invariant 8's correction: a reversal, never an edit. ADR-019. */
    public static final String REFUND_REVERSAL = "REFUND_REVERSAL";

    /**
     * What a {@link #REFUND_REVERSAL} journal used to point back at, and no longer does.
     * <p>
     * Kept because rows written before V29 still carry it. New reversals reference the PAYMENT
     * INTENT instead, so the Ledger can net a payment's refunds against its capture without asking
     * another module -- see {@link #refundReversal}. The header is immutable
     * ({@code tr_ledger_transactions_immutable}, and its own comment names re-pointing a posted
     * journal as the thing it refuses), so the old rows stay as written rather than being
     * rewritten to match.
     * <p>
     * <b>The consequence, stated:</b> a refund posted before V29 is not subtracted by the release
     * job's per-payment sum, so such a payment can over-release. In a real deployment that is a
     * one-off reconciliation at migration time; here it is a handful of dev rows. Rewriting them
     * would be exactly the history edit the ledger exists to make impossible.
     */
    public static final String REFERENCE_REFUND = "REFUND";

    /** Funds clearing the holding period and becoming settleable. SDD 15.1, ADR-031. */
    public static final String FUNDS_RELEASED = "FUNDS_RELEASED";

    /** Available funds committed to a settlement batch. SDD 17.6 invariant 2, ADR-032. */
    public static final String SETTLEMENT_BATCH_CUT = "SETTLEMENT_BATCH_CUT";

    /** The provider confirmed the payout. The one posting where money leaves PayMesh. */
    public static final String PAYOUT_PAID = "PAYOUT_PAID";

    /** The payout failed terminally and the funds go back to available. */
    public static final String PAYOUT_RETURNED = "PAYOUT_RETURNED";

    /** What a settlement journal points back at. */
    public static final String REFERENCE_SETTLEMENT_BATCH = "SETTLEMENT_BATCH";

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

    /**
     * A payment's funds clearing the holding period.
     *
     * <pre>
     *   DEBIT   merchant:mrc_x:pending:INR      -- no longer conditional
     *   CREDIT  merchant:mrc_x:available:INR    -- and now settleable
     * </pre>
     *
     * <h2>BOTH SIDES ARE THE SAME MERCHANT'S LIABILITY, WHICH IS WHY THIS BALANCES TO NOTHING</h2>
     *
     * PayMesh owes exactly as much after this as before. No money is created, no obligation is
     * discharged: a claim stops being conditional. That is the whole reason a release is a
     * transaction rather than a status flag on a payment -- the two liabilities are separate
     * accounts, so the move between them is visible, dated and reversible like any other journal.
     *
     * @param amountMinor what is left pending for this payment, not what was captured. A refund
     *     before release has already debited pending, so releasing the captured figure would push
     *     pending negative and available too high. The caller computes the remainder; this records
     *     it.
     */
    public static LedgerTransaction fundsReleased(
        MerchantId merchantId,
        String paymentIntentId,
        LedgerAccountId merchantPendingAccountId,
        LedgerAccountId merchantAvailableAccountId,
        long amountMinor,
        String currency,
        Instant occurredAt,
        Instant createdAt
    ) {
        return new LedgerTransaction(
            LedgerTransactionId.generate(),
            merchantId,
            FUNDS_RELEASED,
            REFERENCE_PAYMENT_INTENT,
            paymentIntentId,
            currency,
            fundsReleasedIdempotencyKey(paymentIntentId),
            List.of(
                LedgerEntry.debit(merchantPendingAccountId, amountMinor),
                LedgerEntry.credit(merchantAvailableAccountId, amountMinor)
            ),
            occurredAt,
            createdAt
        );
    }

    /**
     * {@code funds-released:pi_<uuid>}, and this key IS the release job's state.
     *
     * <p>The phase-2 plan called for a table tracking which payments had been released. There is
     * none: {@code uq_ledger_transactions_idempotency} (V15) already makes this key unique, so
     * "has this payment been released?" is answered by the ledger that did the releasing. A
     * separate table would be a second copy of that fact, and the failure mode of a second copy is
     * that it disagrees with the first -- the same argument {@code BalanceRepository} makes for
     * summing entries rather than projecting them.
     *
     * <p>Keyed on the PAYMENT rather than on the job run, so two overlapping runs cannot release
     * one payment twice: the second insert loses to the unique index and is a no-op, exactly as a
     * redelivered capture is.
     */
    public static String fundsReleasedIdempotencyKey(String paymentIntentId) {
        return "funds-released:" + paymentIntentId;
    }

    /**
     * A merchant's available balance being committed to a batch.
     *
     * <pre>
     *   DEBIT   merchant:mrc_x:available:INR    -- can no longer be settled again
     *   CREDIT  merchant:mrc_x:in-transit:INR   -- committed to a payout
     * </pre>
     *
     * SDD 17.6 invariant 2, and the reason it is an invariant: without this hop, a payout in flight
     * is either still available (so the next batch settles it a second time) or already gone from
     * the balance (so a failed payout has nowhere to come back to). Both liabilities belong to the
     * same merchant, so this nets to zero against PayMesh's position, exactly like a release.
     */
    public static LedgerTransaction settlementBatchCut(
        MerchantId merchantId,
        String settlementBatchId,
        LedgerAccountId merchantAvailableAccountId,
        LedgerAccountId settlementInTransitAccountId,
        long amountMinor,
        String currency,
        Instant occurredAt,
        Instant createdAt
    ) {
        return new LedgerTransaction(
            LedgerTransactionId.generate(),
            merchantId,
            SETTLEMENT_BATCH_CUT,
            REFERENCE_SETTLEMENT_BATCH,
            settlementBatchId,
            currency,
            settlementBatchCutIdempotencyKey(settlementBatchId),
            List.of(
                LedgerEntry.debit(merchantAvailableAccountId, amountMinor),
                LedgerEntry.credit(settlementInTransitAccountId, amountMinor)
            ),
            occurredAt,
            createdAt
        );
    }

    /**
     * The payout landed.
     *
     * <pre>
     *   DEBIT   merchant:mrc_x:in-transit:INR   -- PayMesh no longer owes it
     *   CREDIT  bank-cash:INR                   -- and no longer has it
     * </pre>
     *
     * <b>The only journal in this ledger where money leaves the platform</b>, which is why it is
     * posted on the provider's confirmation and never on PayMesh's own submission. A debit of a
     * liability against a credit of an asset is the shape of an obligation being discharged with
     * cash; every other journal here moves value between two accounts on the same side.
     */
    public static LedgerTransaction payoutPaid(
        MerchantId merchantId,
        String settlementBatchId,
        LedgerAccountId settlementInTransitAccountId,
        LedgerAccountId bankCashAccountId,
        long amountMinor,
        String currency,
        Instant occurredAt,
        Instant createdAt
    ) {
        return new LedgerTransaction(
            LedgerTransactionId.generate(),
            merchantId,
            PAYOUT_PAID,
            REFERENCE_SETTLEMENT_BATCH,
            settlementBatchId,
            currency,
            payoutPaidIdempotencyKey(settlementBatchId),
            List.of(
                LedgerEntry.debit(settlementInTransitAccountId, amountMinor),
                LedgerEntry.credit(bankCashAccountId, amountMinor)
            ),
            occurredAt,
            createdAt
        );
    }

    /**
     * The payout failed for the last time, and the money goes back.
     *
     * <pre>
     *   DEBIT   merchant:mrc_x:in-transit:INR   -- no longer committed
     *   CREDIT  merchant:mrc_x:available:INR    -- settleable again
     * </pre>
     *
     * SDD 17.6 invariant 3: <b>a new journal, never an edit of the batch's own.</b> The immutability
     * trigger makes that the only available option rather than the disciplined one, and the reason
     * it matters is auditability -- "this batch was cut and then returned" is a different history
     * from "this batch was never cut", and only one of them is what happened.
     */
    public static LedgerTransaction payoutReturned(
        MerchantId merchantId,
        String settlementBatchId,
        LedgerAccountId settlementInTransitAccountId,
        LedgerAccountId merchantAvailableAccountId,
        long amountMinor,
        String currency,
        Instant occurredAt,
        Instant createdAt
    ) {
        return new LedgerTransaction(
            LedgerTransactionId.generate(),
            merchantId,
            PAYOUT_RETURNED,
            REFERENCE_SETTLEMENT_BATCH,
            settlementBatchId,
            currency,
            payoutReturnedIdempotencyKey(settlementBatchId),
            List.of(
                LedgerEntry.debit(settlementInTransitAccountId, amountMinor),
                LedgerEntry.credit(merchantAvailableAccountId, amountMinor)
            ),
            occurredAt,
            createdAt
        );
    }

    /**
     * {@code settlement-batch-cut:stl_<uuid>}, and the same argument as everywhere else in this
     * class: the key is the batch, so a redelivered {@code settlement.batch_cut} event posts
     * nothing on its second arrival.
     */
    public static String settlementBatchCutIdempotencyKey(String settlementBatchId) {
        return "settlement-batch-cut:" + settlementBatchId;
    }

    public static String payoutPaidIdempotencyKey(String settlementBatchId) {
        return "payout-paid:" + settlementBatchId;
    }

    public static String payoutReturnedIdempotencyKey(String settlementBatchId) {
        return "payout-returned:" + settlementBatchId;
    }

    /**
     * The correction for a refund: the capture posting, in reverse.
     *
     * <pre>
     *   DEBIT   merchant:mrc_x:pending:INR    -- PayMesh owes the merchant less
     *   CREDIT  provider-clearing:INR         -- and is owed less by the provider
     * </pre>
     *
     * <b>The original journal is untouched.</b> It cannot be touched --
     * {@code tr_ledger_entries_immutable} refuses UPDATE and DELETE -- and that is the point rather
     * than an obstacle: both journals stay in the history, so "what happened" and "what the balance
     * is" remain separate facts.
     * <p>
     * The reference points at the REFUND, not at the payment. A payment can be refunded in several
     * parts, and each part is its own correction with its own reason to exist.
     */
    public static LedgerTransaction refundReversal(
        MerchantId merchantId,
        String refundId,
        String paymentIntentId,
        LedgerAccountId merchantPendingAccountId,
        LedgerAccountId providerClearingAccountId,
        long amountMinor,
        String currency,
        Instant occurredAt,
        Instant createdAt
    ) {
        return new LedgerTransaction(
            LedgerTransactionId.generate(),
            merchantId,
            REFUND_REVERSAL,
            // REFERENCE_PAYMENT_INTENT, NOT THE REFUND, AND THE REFUND IS NOT LOST.
            //
            // Pointing this at the payment is what lets the Ledger answer "how much of this
            // payment is still pending?" from its own entries -- sum the pending-account lines of
            // every transaction referencing this intent, and a partial refund is already subtracted.
            // Without it the release job would have to ask Payment how much had been refunded, and
            // ModuleBoundaryTest seals that arrow in both directions with empty allowlists
            // (ADR-018 section 6). The Ledger is handed paymentIntentId by the refund event; it
            // simply was not storing it.
            //
            // The refund id stays in the idempotency key below, so nothing is unrecoverable: the
            // question "which refund caused this journal?" is answered by refund-reversal:ref_x.
            REFERENCE_PAYMENT_INTENT,
            paymentIntentId,
            currency,
            refundReversalIdempotencyKey(refundId),
            List.of(
                LedgerEntry.debit(merchantPendingAccountId, amountMinor),
                LedgerEntry.credit(providerClearingAccountId, amountMinor)
            ),
            occurredAt,
            createdAt
        );
    }

    /**
     * {@code refund-reversal:ref_<uuid>}.
     * <p>
     * KEYED ON THE REFUND, and it has to be. The payment's key is already taken by its capture
     * journal, and a payment may be refunded in several parts -- keying on the payment would
     * collide on the first reversal and silently refuse every later partial refund, which is the
     * worst possible failure mode: money returned to a customer and never recorded.
     */
    public static String refundReversalIdempotencyKey(String refundId) {
        return "refund-reversal:" + refundId;
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
