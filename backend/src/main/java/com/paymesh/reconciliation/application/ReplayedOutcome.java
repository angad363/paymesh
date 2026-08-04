package com.paymesh.reconciliation.application;

/**
 * What this module asks a capability to record, in this module's own words.
 * <p>
 * These four names are the same four {@code ProviderOutcome} carries, and restating them rather than
 * importing them is the same trade {@code SimulatedOutcome} makes on the other side of the
 * simulator's boundary: the application layer must not import {@code com.paymesh.payment}, so the
 * translation happens in the one adapter that is allowed to name both. Four lines of duplication
 * against a job that could never be extracted from Payment.
 */
public enum ReplayedOutcome {

    AUTHORIZED,
    SUCCEEDED,
    FAILED,
    REQUIRES_ACTION
}
