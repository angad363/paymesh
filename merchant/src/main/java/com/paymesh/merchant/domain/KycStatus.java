package com.paymesh.merchant.domain;

/**
 * {@code SUBMITTED -> APPROVED | REJECTED}. Terminal either way.
 * <p>
 * A rejected merchant submits again rather than reopening the old submission, so every decision
 * stays on the record. Reopening would let one row be approved, rejected and approved again with
 * only the last outcome visible.
 */
public enum KycStatus {
    SUBMITTED,
    APPROVED,
    REJECTED
}
