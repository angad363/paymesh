package com.paymesh.settlement.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Duration;
import java.time.Instant;

/**
 * The attempt to move a batch's money to the merchant's bank. SDD 17.2, ADR-032.
 *
 * <h2>SEPARATE FROM THE BATCH, AND THAT IS WHAT MAKES A FAILURE SURVIVABLE</h2>
 *
 * The batch is the amount and the statement; the payout is the attempt. Keeping them apart is what
 * lets a payout fail terminally and return its funds while the batch that described them stays
 * exactly as written -- the ledger's own rule about corrections, applied one layer up. Folded into
 * one row, "what we owed" and "how it went" would share a lifecycle and the first would be rewritten
 * by the second.
 *
 * <h2>The retry budget is ADR-025's shape, not an infinite loop</h2>
 *
 * Bounded attempts, a terminal state, and a log line. A payout that has exhausted its budget is
 * FAILED and its funds go back to available, where the next batch will pick them up -- so a
 * permanently unreachable provider costs a merchant a delay, never their money.
 */
public record Payout(
    PayoutId payoutId,
    SettlementBatchId settlementBatchId,
    MerchantId merchantId,
    long amountMinor,
    String currency,
    String destination,
    PayoutStatus status,
    int attempts,
    Instant nextAttemptAt,
    String lastError,
    String providerReference,
    Instant createdAt,
    Instant updatedAt
) {

    /** Long enough to be honest about a bank being slow, short enough to see in a demo. */
    private static final int MAX_ATTEMPTS = 5;

    public Payout {
        if (payoutId == null || settlementBatchId == null || merchantId == null) {
            throw new IllegalArgumentException("A payout needs an id, a batch and a merchant");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A payout moves a positive amount");
        }

        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("A payout needs an ISO 4217 currency");
        }

        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("A payout needs somewhere to go");
        }

        if (status == null || nextAttemptAt == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("A payout needs a status and timestamps");
        }

        if (attempts < 0) {
            throw new IllegalArgumentException("A payout cannot have been attempted a negative number of times");
        }
    }

    public static Payout create(
        SettlementBatch batch, String destination, Instant now
    ) {
        return new Payout(
            PayoutId.generate(),
            batch.settlementBatchId(),
            batch.merchantId(),
            batch.netAmountMinor(),
            batch.currency(),
            destination,
            PayoutStatus.PENDING,
            0,
            // Due immediately. The batch has already moved the money into in-transit, so there is
            // nothing to wait for -- the delay would only widen the window in which a merchant's
            // balance shows money they can neither settle nor spend.
            now,
            null,
            null,
            now,
            now
        );
    }

    /** The provider accepted the submission. The answer comes back as a callback. */
    public Payout submitted(String providerReference, Instant now, Duration answerTimeout) {
        return new Payout(
            payoutId, settlementBatchId, merchantId, amountMinor, currency, destination,
            PayoutStatus.SUBMITTED,
            attempts + 1,
            // NOT a retry: the row is due again only so a submission whose callback never arrives
            // is visible rather than silently stuck. Resubmitting is safe -- the provider keys on
            // this payout's own id -- which is the only reason this is allowed to come round again.
            now.plus(answerTimeout),
            null,
            providerReference,
            createdAt,
            now
        );
    }

    /**
     * The submission itself failed. Retried until the budget is gone, then FAILED.
     *
     * @param retryDelay how long before the next attempt. Fixed rather than exponential: a bank
     *     that refused a transfer is not a bank being rate-limited, and five attempts at a steady
     *     interval is easier to reason about than a curve nobody reads twice
     */
    public Payout submissionFailed(String error, Instant now, Duration retryDelay) {
        int used = attempts + 1;

        return new Payout(
            payoutId, settlementBatchId, merchantId, amountMinor, currency, destination,
            used >= MAX_ATTEMPTS ? PayoutStatus.FAILED : PayoutStatus.PENDING,
            used,
            now.plus(retryDelay),
            truncate(error),
            providerReference,
            createdAt,
            now
        );
    }

    /** The provider confirmed the money landed. */
    public Payout paid(Instant now) {
        return terminal(PayoutStatus.PAID, null, now);
    }

    /** The provider says the money came back. Terminal, and the batch returns its funds. */
    public Payout failed(String error, Instant now) {
        return terminal(PayoutStatus.FAILED, error, now);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** Whether the last submission used up the budget. Read by the caller deciding to return funds. */
    public boolean hasExhaustedBudget() {
        return status == PayoutStatus.FAILED;
    }

    private Payout terminal(PayoutStatus target, String error, Instant now) {
        if (status.isTerminal()) {
            throw new PayoutNotOpenException(payoutId, status);
        }

        return new Payout(
            payoutId, settlementBatchId, merchantId, amountMinor, currency, destination,
            target, attempts, nextAttemptAt, truncate(error), providerReference, createdAt, now
        );
    }

    /** The column is 500 and a provider's error text is not this platform's to bound. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }

        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
