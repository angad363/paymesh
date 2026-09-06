package com.paymesh.shared.idempotency.domain;

/**
 * Only two states, and deliberately no FAILED one.
 * <p>
 * A record that never reaches COMPLETED is deleted rather than marked, because the server that
 * failed does not know what it did. Recording "this key failed" would pin a legitimate retry to
 * an answer nobody can justify. See ADR-009.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
