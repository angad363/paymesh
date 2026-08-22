package com.paymesh.shared.audit;

/**
 * How a privileged action records itself in the audit log (SDD 19.3, ADR-035).
 *
 * <h2>THIS PORT LIVES IN {@code shared}, AND THAT IS WHAT KEEPS AUDIT A LEAF</h2>
 *
 * Merchant, Identity and Webhook call {@link #record} without importing anything from
 * {@code com.paymesh.audit} -- they depend on this interface, exactly as they depend on
 * {@code MerchantId} and {@code Clock}. The single implementation lives in the Audit module's
 * infrastructure and is the only thing that reaches the other way. {@code ModuleBoundaryTest} keeps
 * every arrow pointing at Audit, never out of it, the same shape {@code AuditRecorder}'s siblings
 * ({@code HoldingPeriodPolicy}, the event {@code EventHandler}) have.
 *
 * <h2>CALLED INSIDE THE CALLER'S TRANSACTION, ON PURPOSE</h2>
 *
 * A privileged service invokes this from within the same {@code TransactionTemplate} that commits
 * the action, so the audit row and the action commit together. A committed suspension always carries
 * its audit event; a rolled-back one leaves no false trail. This is the "never unauditable" half of
 * the governing invariant, the same reasoning the transactional outbox uses -- and it means a
 * failure to record IS a failure to act, which for a security log is correct: an action that cannot
 * be audited must not silently happen.
 */
public interface AuditRecorder {

    /**
     * Records one action. Hashes the entry's {@code before}, {@code after} and {@code ip} before
     * writing (see {@link AuditEntry}), so no plaintext secret reaches the log.
     */
    void record(AuditEntry entry);
}
