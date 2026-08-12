package com.paymesh.ledger.application;

import com.paymesh.ledger.domain.LedgerAccount;
import com.paymesh.ledger.domain.LedgerTransaction;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Moves cleared funds from pending to available. SDD 15.1, ADR-031.
 *
 * <h2>NO STATE OF ITS OWN, AND THAT IS THE DESIGN</h2>
 *
 * The plan reserved a migration for "release job state". There is none. Two facts the Ledger
 * already holds answer everything this job needs to know:
 * <ul>
 *   <li><b>Has this payment been released?</b> {@code uq_ledger_transactions_idempotency} on
 *       {@code funds-released:pi_x}. Two overlapping runs cannot double-release; the loser hits the
 *       unique index.</li>
 *   <li><b>How much is left to release?</b> The signed sum of pending-account lines across every
 *       journal referencing that payment. A partial refund is already subtracted, and a released
 *       payment sums to zero because its own release is in the sum.</li>
 * </ul>
 * A state table would be a second copy of both, and the failure mode of a second copy is that it
 * disagrees with the first -- the argument {@code BalanceRepository} already makes for summing
 * entries rather than projecting them.
 *
 * <h2>One transaction per payment, and one bad row costs one payment</h2>
 *
 * Open item 2's shape, applied from the start rather than retrofitted: candidates come back as
 * plain data, each is posted in its own transaction inside a per-item try, and a payment that
 * cannot be released is counted and skipped rather than ending the pass.
 */
public final class ReleaseAvailableFundsService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseAvailableFundsService.class);

    private final LedgerTransactionRepository transactions;
    private final LedgerAccountRepository accounts;
    private final BalanceRepository balances;
    private final HoldingPeriodPolicy holdingPeriods;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;

    public ReleaseAvailableFundsService(
        LedgerTransactionRepository transactions,
        LedgerAccountRepository accounts,
        BalanceRepository balances,
        HoldingPeriodPolicy holdingPeriods,
        org.springframework.transaction.support.TransactionTemplate transactionTemplate,
        Clock clock,
        int batchSize
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Release batch size must be at least 1");
        }

        this.transactions = transactions;
        this.accounts = accounts;
        this.balances = balances;
        this.holdingPeriods = holdingPeriods;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public ReleaseResult release() {
        Instant now = Instant.now(clock);
        List<ReleasableCapture> candidates = transactions.findUnreleasedCaptures(batchSize);

        int released = 0;
        int held = 0;
        int errored = 0;

        for (ReleasableCapture candidate : candidates) {
            try {
                // PARSED AND DECIDED INSIDE THE TRY. The candidate is raw data, so a row the
                // mapper or the id parser chokes on costs one payment rather than the pass.
                if (releaseOne(candidate, now)) {
                    released++;
                } else {
                    held++;
                }
            } catch (RuntimeException failure) {
                errored++;

                log.warn(
                    "Could not release funds paymentIntentId={} merchantId={}",
                    candidate.paymentIntentId(), candidate.merchantId(), failure
                );
            }
        }

        return new ReleaseResult(candidates.size(), released, held, errored);
    }

    private boolean releaseOne(ReleasableCapture candidate, Instant now) {
        MerchantId merchantId = MerchantId.from(candidate.merchantId());

        if (!hasCleared(merchantId, candidate.capturedAt(), now)) {
            return false;
        }

        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            long remaining = balances.pendingRemainingForPayment(candidate.paymentIntentId());

            // Nothing left: fully refunded before it ever cleared. Not an error and not a release --
            // posting a zero-amount journal would be a row that says nothing happened.
            if (remaining <= 0) {
                return false;
            }

            LedgerAccount pending = accounts.open(
                LedgerAccount.merchantPending(merchantId, candidate.currency(), now)
            );
            LedgerAccount available = accounts.open(
                LedgerAccount.merchantAvailable(merchantId, candidate.currency(), now)
            );

            transactions.post(LedgerTransaction.fundsReleased(
                merchantId,
                candidate.paymentIntentId(),
                pending.ledgerAccountId(),
                available.ledgerAccountId(),
                remaining,
                candidate.currency(),
                now,
                now
            ));

            return true;
        }));
    }

    private boolean hasCleared(MerchantId merchantId, Instant capturedAt, Instant now) {
        return !now.isBefore(capturedAt.plus(holdingPeriods.forMerchant(merchantId)));
    }

    /** What one pass did. Counted so the scheduled bean can log something worth reading. */
    public record ReleaseResult(int examined, int released, int held, int errored) {
    }
}
