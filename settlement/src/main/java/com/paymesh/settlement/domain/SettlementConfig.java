package com.paymesh.settlement.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Duration;
import java.time.Instant;

/**
 * When one merchant's captured funds become settleable. SDD 17.4, ADR-031.
 *
 * <h2>THREE SETTINGS NOW, NOT SDD 17.4's FIVE</h2>
 *
 * ADR-031 shipped only the holding period, on the grounds that "a field whose meaning is decided by
 * a capability that does not exist is a field that gets it wrong", and said the rest would arrive
 * with PR 4. Two of them have (V30): a payout destination and a minimum, both read by the batch
 * job.
 * <p>
 * <b>The payout SCHEDULE is still not here</b>, and now for a better reason than "no reader". The
 * job's interval is the schedule. A per-merchant cron expression would be a second schedule that
 * can disagree with the first, and nothing in this platform can run a batch at a time the job is
 * not awake anyway.
 *
 * @param holdingPeriod how long after capture funds stay pending. Zero is legitimate -- "release on
 *     the next run" is a real policy for a trusted merchant, and it is the simplest way to see the
 *     release path work. Negative is not a policy, it is a sign error, and the domain refuses it
 *     alongside {@code ck_settlement_configs_holding_period}.
 * @param payoutDestination where the money goes, or NULL for "not configured". A merchant without
 *     one is never batched: cutting a batch would move their funds out of available and into a
 *     transit account with nowhere to go, which is strictly worse than leaving them settleable
 * @param minimumPayoutMinor below this, cutting a batch costs more than it moves. One means "any
 *     positive balance", which is the default and is not the same as zero -- zero would be
 *     indistinguishable from having no minimum at all
 */
public record SettlementConfig(
    MerchantId merchantId,
    Duration holdingPeriod,
    String payoutDestination,
    long minimumPayoutMinor,
    Instant createdAt,
    Instant updatedAt
) {

    public SettlementConfig {
        if (merchantId == null) {
            throw new IllegalArgumentException("Settlement config merchant cannot be null");
        }

        if (holdingPeriod == null) {
            throw new IllegalArgumentException("Settlement config holding period cannot be null");
        }

        if (holdingPeriod.isNegative()) {
            throw new IllegalArgumentException(
                "Settlement config holding period cannot be negative"
            );
        }

        if (minimumPayoutMinor <= 0) {
            throw new IllegalArgumentException(
                "Settlement config minimum payout must be positive, got " + minimumPayoutMinor
            );
        }

        payoutDestination = payoutDestination == null || payoutDestination.isBlank()
            ? null
            : payoutDestination.strip();

        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Settlement config timestamps cannot be null");
        }
    }

    /** Whether this merchant can be paid at all. No destination, no batch. */
    public boolean isPayable() {
        return payoutDestination != null;
    }

    /** Whether {@code amountMinor} is worth cutting a batch for. */
    public boolean meetsMinimum(long amountMinor) {
        return amountMinor >= minimumPayoutMinor;
    }

    /** Whether funds captured at {@code capturedAt} have cleared the period by {@code now}. */
    public boolean hasCleared(Instant capturedAt, Instant now) {
        return !now.isBefore(capturedAt.plus(holdingPeriod));
    }

    /**
     * PUT semantics: every setting is replaced, not merged. A caller that omits the destination is
     * clearing it, which is the only reading of PUT that does not require a client to know what it
     * did not send.
     */
    public SettlementConfig with(
        Duration holdingPeriod,
        String payoutDestination,
        long minimumPayoutMinor,
        Instant updatedAt
    ) {
        return new SettlementConfig(
            merchantId, holdingPeriod, payoutDestination, minimumPayoutMinor, createdAt, updatedAt
        );
    }
}
