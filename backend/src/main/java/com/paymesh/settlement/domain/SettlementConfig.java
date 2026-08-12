package com.paymesh.settlement.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Duration;
import java.time.Instant;

/**
 * When one merchant's captured funds become settleable. SDD 17.4, ADR-031.
 *
 * <h2>ONE SETTING, NOT SDD 17.4's FIVE</h2>
 *
 * The SDD also specifies a payout schedule, a minimum payout amount, a settlement currency and a
 * payout account. All four belong to Settlement (PR 4) and none has a reader yet: a schedule
 * nothing runs to, a minimum nothing compares against, an account nothing pays into. A field whose
 * meaning is decided by a capability that does not exist is a field that gets it wrong, and this
 * codebase has spent three ADRs making unreachable values reachable.
 * <p>
 * The holding period is different. It is the input to the release job, so it lands with the thing
 * that reads it.
 *
 * @param holdingPeriod how long after capture funds stay pending. Zero is legitimate -- "release on
 *     the next run" is a real policy for a trusted merchant, and it is the simplest way to see the
 *     release path work. Negative is not a policy, it is a sign error, and the domain refuses it
 *     alongside {@code ck_settlement_configs_holding_period}.
 */
public record SettlementConfig(
    MerchantId merchantId,
    Duration holdingPeriod,
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

        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Settlement config timestamps cannot be null");
        }
    }

    /** Whether funds captured at {@code capturedAt} have cleared the period by {@code now}. */
    public boolean hasCleared(Instant capturedAt, Instant now) {
        return !now.isBefore(capturedAt.plus(holdingPeriod));
    }

    public SettlementConfig withHoldingPeriod(Duration holdingPeriod, Instant updatedAt) {
        return new SettlementConfig(merchantId, holdingPeriod, createdAt, updatedAt);
    }
}
