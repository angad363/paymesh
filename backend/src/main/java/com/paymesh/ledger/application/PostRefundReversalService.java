package com.paymesh.ledger.application;

import com.paymesh.ledger.domain.LedgerAccount;
import com.paymesh.ledger.domain.LedgerTransaction;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Posts the reversal journal for a refund. THE CORRECTION MECHANISM V15 DESIGNED FOR.
 *
 * <h2>A NEW JOURNAL IN THE OPPOSITE DIRECTION, NEVER AN EDIT</h2>
 *
 * SDD 15.6's eighth invariant, and V15 made it the only possibility rather than the disciplined
 * choice: {@code tr_ledger_entries_immutable} refuses UPDATE and DELETE outright, so there is no
 * "undo the capture" operation to reach for. The capture journal stays exactly as posted and this
 * writes a second one:
 *
 * <pre>
 *   capture:   DEBIT  provider-clearing    CREDIT merchant-pending
 *   reversal:  DEBIT  merchant-pending     CREDIT provider-clearing
 * </pre>
 *
 * The merchant's pending liability goes down -- PayMesh owes them less, because the money went back
 * to the customer -- and the receivable from the provider goes down with it. Both journals remain
 * in the history, which is the whole reason a ledger is append-only: "what happened" and "what the
 * balance is" stay separate facts, and the first is never rewritten to make the second convenient.
 *
 * <h2>The accounts are the same two, addressed the same way</h2>
 *
 * No refund-specific account. SDD 15.1 lists {@code REFUND_RECEIVABLE}, which models money owed by
 * a provider who has agreed to refund but not yet sent it -- a settlement-timing distinction this
 * platform cannot make, because it learns of the refund and the money movement in the same
 * callback. Adding the account now would produce a third balance that is always zero.
 */
public final class PostRefundReversalService {

    private static final Logger log = LoggerFactory.getLogger(PostRefundReversalService.class);

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final Clock clock;

    public PostRefundReversalService(
        LedgerAccountRepository accounts,
        LedgerTransactionRepository transactions,
        Clock clock
    ) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @return the reversal journal, or EMPTY when there was nothing to post
     */
    public Optional<LedgerTransaction> post(
        MerchantId merchantId,
        String refundId,
        String paymentIntentId,
        long amountMinor,
        String currency,
        Instant occurredAt
    ) {
        if (amountMinor <= 0) {
            log.info(
                "Refund succeeded with no amount, no ledger reversal refundId={} merchantId={}",
                refundId, merchantId.value()
            );

            return Optional.empty();
        }

        // KEYED ON THE REFUND, not on the payment. The payment key is already taken by the capture
        // journal, and a payment can legitimately be refunded several times in parts -- so
        // "payment-captured:pi_x" would collide on the first reversal and every later partial
        // refund would be silently refused.
        String idempotencyKey = LedgerTransaction.refundReversalIdempotencyKey(refundId);

        Optional<LedgerTransaction> alreadyPosted = transactions.findByIdempotencyKey(idempotencyKey);

        if (alreadyPosted.isPresent()) {
            log.info(
                "Refund reversal is already posted, nothing to do idempotencyKey={} merchantId={}",
                idempotencyKey, merchantId.value()
            );

            return alreadyPosted;
        }

        Instant now = Instant.now(clock);

        LedgerAccount providerClearing = accounts.open(
            LedgerAccount.providerClearing(currency, now)
        );

        // WHICH OF THE MERCHANT'S TWO LIABILITIES THIS COMES OUT OF (ADR-031).
        //
        // Before the funds are released they sit in pending, and that is where the money goes back
        // from. After release they are in available -- pending holds nothing for this payment any
        // more -- so debiting pending would drive it negative while leaving available claiming
        // money the merchant no longer has. Available is the figure Settlement pays against, so
        // that error is one that gets paid out.
        //
        // The discriminator is the release journal itself: releasing moves ALL of a payment's
        // remaining pending, so "released" and "nothing of it is pending" are the same fact, and
        // the ledger already records it under a unique key.
        // AND THE CHECK IS TAKEN UNDER A LOCK. The release job locks the same rows before it reads
        // how much is left to release, so the two orderings that matter -- reversal then release,
        // release then reversal -- are the only two that can happen. Without it both can read a
        // world without the other, and the payment over-releases into the balance Settlement pays
        // out against.
        transactions.lockPaymentJournals(paymentIntentId);

        boolean released = transactions
            .findByIdempotencyKey(LedgerTransaction.fundsReleasedIdempotencyKey(paymentIntentId))
            .isPresent();

        LedgerAccount merchantSide = accounts.open(released
            // AND IT MAY GO NEGATIVE. A merchant refunding after being paid out owes PayMesh the
            // money, and a payment platform has to be able to say so. Clamping at zero would be a
            // second copy of the truth that disagrees with the entries.
            ? LedgerAccount.merchantAvailable(merchantId, currency, now)
            : LedgerAccount.merchantPending(merchantId, currency, now)
        );

        // Not caught, for the same reason the capture posting is not: a concurrent duplicate rolls
        // back the dispatcher's transaction along with the inbox row, and redelivery finds the
        // winner. See PostPaymentCapturedService.
        LedgerTransaction posted = transactions.post(LedgerTransaction.refundReversal(
            merchantId,
            refundId,
            paymentIntentId,
            merchantSide.ledgerAccountId(),
            providerClearing.ledgerAccountId(),
            amountMinor,
            currency,
            occurredAt,
            now
        ));

        log.info(
            "Posted refund reversal ledgerTransactionId={} refundId={} amountMinor={} {}",
            posted.ledgerTransactionId().value(), refundId, amountMinor, posted.currency()
        );

        return Optional.of(posted);
    }
}
