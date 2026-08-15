package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.PayoutId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Hands due payouts to the provider. SDD 17.2, ADR-032.
 *
 * <h2>SUBMISSION IS NOT CONFIRMATION, AND NOTHING HERE POSTS TO THE LEDGER</h2>
 *
 * A 2xx from the provider means it accepted the instruction, not that a bank moved money. The
 * ledger's {@code payout.paid} journal is posted from the provider's signed callback and from
 * nowhere else -- because that journal credits {@code BANK_CASH}, the one place in this ledger where
 * money leaves the platform, and posting it on PayMesh's own optimism would make the platform's cash
 * position a guess.
 *
 * <h2>One transaction per payout, and one bad row costs one payout</h2>
 *
 * The candidates come back as raw ids, each is claimed under its own row lock, and a payout that
 * throws is counted and skipped. Open item 2's shape, which this codebase has now paid for once.
 *
 * <h2>A resubmission is safe, which is why a stuck payout is allowed to come round again</h2>
 *
 * The provider keys on the payout's own id ({@code uq_provider_payouts_external_reference}), so
 * resubmitting returns the original rather than moving money twice. That is what makes it safe to
 * put SUBMITTED rows back in the queue after an answer timeout instead of leaving them stuck
 * forever waiting for a callback that was lost.
 */
public final class SubmitPayoutsService {

    private static final Logger log = LoggerFactory.getLogger(SubmitPayoutsService.class);

    private final PayoutRepository payouts;
    private final SettlementBatchRepository batches;
    private final CompleteSettlementService completeSettlement;
    private final PayoutGateway gateway;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration retryDelay;
    private final Duration answerTimeout;

    public SubmitPayoutsService(
        PayoutRepository payouts,
        SettlementBatchRepository batches,
        CompleteSettlementService completeSettlement,
        PayoutGateway gateway,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize,
        Duration retryDelay,
        Duration answerTimeout
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Payout batch size must be at least 1");
        }

        this.payouts = payouts;
        this.batches = batches;
        this.completeSettlement = completeSettlement;
        this.gateway = gateway;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.retryDelay = retryDelay;
        this.answerTimeout = answerTimeout;
    }

    public SubmitResult submit() {
        List<String> due = payouts.findDue(Instant.now(clock), batchSize);

        int submitted = 0;
        int refused = 0;
        int errored = 0;
        int gone = 0;

        for (String payoutId : due) {
            try {
                switch (submitOne(payoutId)) {
                    case SUBMITTED -> submitted++;
                    case REFUSED -> refused++;
                    case GONE -> gone++;
                }
            } catch (RuntimeException failure) {
                // THE ID IS PARSED INSIDE THIS TRY. A malformed row costs one payout rather than
                // the pass, and it is still due, so the next pass sees it again.
                errored++;

                log.warn("Payout {} threw and will be retried", payoutId, failure);
            }
        }

        return new SubmitResult(due.size(), submitted, refused, gone, errored);
    }

    private Attempt submitOne(String rawPayoutId) {
        PayoutId payoutId = PayoutId.from(rawPayoutId);

        Payout claimed = transactions.execute(status -> payouts.findForUpdate(payoutId)
            .filter(payout -> !payout.isTerminal())
            .orElse(null));

        if (claimed == null) {
            // Answered by a callback between the candidate read and the lock. SKIP LOCKED and this
            // filter exist so that is a no-op rather than a wait or an error.
            return Attempt.GONE;
        }

        // THE HTTP CALL IS OUTSIDE THE TRANSACTION, unlike the simulator's dispatcher, which
        // documents holding a row lock across a socket as an accepted cost. Here it is avoidable:
        // the provider deduplicates on the payout id, so a submission that lands and then fails to
        // record is retried harmlessly. That trade -- a possible duplicate REQUEST for no held lock
        // -- is the right way round when the request moves money.
        String providerReference;

        try {
            providerReference = gateway.submit(claimed);
        } catch (PayoutSubmissionFailedException failure) {
            transactions.execute(status -> {
                Payout attempted = payouts.save(
                    claimed.submissionFailed(failure.getMessage(), Instant.now(clock), retryDelay)
                );

                // THE BUDGET RUNNING OUT HAS TO RETURN THE MONEY, NOT JUST STOP TRYING. The batch
                // already moved the merchant's funds into in-transit, so a payout abandoned without
                // this leaves them in an account nothing settles from and nothing pays out of --
                // money that is neither theirs to spend nor PayMesh's to keep.
                if (attempted.hasExhaustedBudget()) {
                    batches.find(attempted.merchantId(), attempted.settlementBatchId()).ifPresent(
                        batch -> completeSettlement.returnFunds(
                            attempted, batch,
                            "Exhausted " + attempted.attempts() + " submission attempts: "
                                + failure.getMessage()
                        )
                    );
                }

                return attempted;
            });

            return Attempt.REFUSED;
        }

        Instant now = Instant.now(clock);

        transactions.execute(status ->
            payouts.save(claimed.submitted(providerReference, now, answerTimeout))
        );

        return Attempt.SUBMITTED;
    }

    private enum Attempt {
        SUBMITTED,
        REFUSED,
        GONE
    }

    /**
     * @param refused submissions the provider would not take. A payout among these that used its
     *     last attempt is now FAILED, and the funds go back to available when the callback service
     *     or the next sweep returns them
     */
    public record SubmitResult(int examined, int submitted, int refused, int gone, int errored) {
    }
}
