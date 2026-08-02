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
 * Posts the journal for a captured payment. The only writer the ledger has.
 *
 * <h2>WHY THERE IS NO {@code POST /internal/v1/ledger/transactions}</h2>
 *
 * SDD 15.3 specifies one, and it is deliberately absent. Nothing would call it: the platform is one
 * deployable (ADR-001), the only thing that posts is this service, and the only thing that drives
 * this service is an event. An HTTP endpoint would be a second, authenticated, idempotency-keyed
 * way to reach code that already has exactly one caller -- and, worse, a way for anything holding a
 * service token to write into the financial source of truth without an originating event to
 * reconcile against.
 * <p>
 * <b>Every posting traces to an event that traces to a state change.</b> That is a stronger
 * property than the endpoint would give, and it is free while the ledger is in-process. The
 * endpoint arrives with the extraction, and the extraction is deliberately last (SDD 30.1).
 * ADR-018 section 6.
 *
 * <h2>What owns the transaction</h2>
 *
 * Nothing here. {@code EventDispatcher} wraps the whole handler call -- inbox claim and this
 * posting -- in one transaction, so the record of having consumed the event and the journal it
 * produced commit together or neither does. That is what makes redelivery safe, and it is also
 * where the deferred balance trigger fires.
 */
public final class PostPaymentCapturedService {

    private static final Logger log = LoggerFactory.getLogger(PostPaymentCapturedService.class);

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final Clock clock;

    public PostPaymentCapturedService(
        LedgerAccountRepository accounts,
        LedgerTransactionRepository transactions,
        Clock clock
    ) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @return the journal, whether it was written now or already existed; EMPTY when there was
     *     nothing to post. Optional rather than {@code null} because the empty case is a real
     *     outcome a second caller will meet -- the reversal path Refund needs is the obvious one --
     *     and a bare {@code null} here would be an NPE in that caller with nothing in the signature
     *     to warn them.
     */
    public Optional<LedgerTransaction> post(
        MerchantId merchantId,
        String paymentIntentId,
        long capturedAmountMinor,
        String currency,
        Instant occurredAt
    ) {
        // A SUCCEEDED payment that captured nothing posts nothing. It is reachable -- a provider
        // callback can report success on a zero capture -- and a journal of two zero entries would
        // balance perfectly while recording no movement of money. LedgerEntry refuses a zero
        // amount, so without this the handler would throw and the event would retry forever.
        if (capturedAmountMinor <= 0) {
            log.info(
                "Payment succeeded with nothing captured, no ledger posting"
                    + " paymentIntentId={} merchantId={}",
                paymentIntentId, merchantId.value()
            );

            return Optional.empty();
        }

        String idempotencyKey = LedgerTransaction.paymentCapturedIdempotencyKey(paymentIntentId);

        // THE FAST PATH, AND THE ONLY ONE THAT CAN RECOVER IN-TRANSACTION. The unique index is
        // still the guard -- see the catch-free comment on the post() call below -- but the
        // ordinary duplicate has to be caught by a read, because by the time the index has spoken
        // this transaction can no longer do anything except roll back.
        Optional<LedgerTransaction> alreadyPosted = transactions.findByIdempotencyKey(idempotencyKey);

        if (alreadyPosted.isPresent()) {
            log.info(
                "Payment capture is already posted, nothing to do idempotencyKey={} merchantId={}",
                idempotencyKey, merchantId.value()
            );

            return alreadyPosted;
        }

        Instant now = Instant.now(clock);

        // OPENED ON FIRST USE, NOT SEEDED BY A MIGRATION. Seeding would need the currency list up
        // front -- it is not known, a merchant picks it per order -- and a hook on merchant
        // registration to open each merchant's account, coupling two modules for nothing. An
        // account with no entries is indistinguishable from one that does not exist.
        //
        // open() resolves the concurrent-first-use race itself, without raising an error; see
        // LedgerAccountRepository.open for why an error here would poison the whole posting.
        LedgerAccount providerClearing = accounts.open(
            LedgerAccount.providerClearing(currency, now)
        );

        LedgerAccount merchantPending = accounts.open(
            LedgerAccount.merchantPending(merchantId, currency, now)
        );

        // NOT WRAPPED IN A CATCH, DELIBERATELY.
        //
        // If two deliveries of one capture race past the read above, the loser violates
        // uq_ledger_transactions_idempotency and LedgerTransactionAlreadyPostedException propagates
        // out of this method. That is the correct outcome and the recovery is already built:
        //
        //   1. The exception rolls back the dispatcher's transaction, which contains BOTH this
        //      posting and the inbox row claiming the event. Neither commits.
        //   2. The event is therefore still unclaimed, so the relay delivers it again.
        //   3. On redelivery the winner's row is committed, the read above finds it, and the
        //      handler returns without writing anything.
        //
        // Catching it here could not do better -- the transaction is already aborted by then and
        // the recovery read would fail with "current transaction is aborted" -- and it would be
        // worse, because it would swallow the one signal that makes the retry happen. At-least-once
        // delivery is what turns this from a lost posting into a slow one.
        LedgerTransaction posted = transactions.post(LedgerTransaction.paymentCaptured(
            merchantId,
            paymentIntentId,
            providerClearing.ledgerAccountId(),
            merchantPending.ledgerAccountId(),
            capturedAmountMinor,
            currency,
            occurredAt,
            now
        ));

        log.info(
            "Posted ledger transaction ledgerTransactionId={} merchantId={} amountMinor={} {}",
            posted.ledgerTransactionId().value(), merchantId.value(), capturedAmountMinor,
            posted.currency()
        );

        return Optional.of(posted);
    }
}
