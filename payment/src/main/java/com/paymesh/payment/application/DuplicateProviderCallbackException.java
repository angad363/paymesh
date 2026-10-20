package com.paymesh.payment.application;

/**
 * The same provider event, delivered again: {@code pk_provider_callbacks} refused the insert.
 * <p>
 * <b>It must escape the transaction, and that is the entire mechanism</b> (ADR-012). The callback row
 * is inserted inside the same transaction as the state change, so this exception rolls the whole
 * thing back -- no transition, no state-history row, no outbox event. The caller catches it outside
 * the transaction and answers {@code 200 {"outcome": "DUPLICATE"}}.
 * <p>
 * Catching it <em>inside</em> the transaction and carrying on would not work and must not be
 * attempted: the JPA transaction is already marked rollback-only by then, so every later write would
 * fail at commit anyway. The rollback is not a side effect of the design; it is the design.
 */
public class DuplicateProviderCallbackException extends RuntimeException {

    public DuplicateProviderCallbackException(String provider, String externalEventId) {
        super("Provider callback " + provider + "/" + externalEventId + " has already been recorded");
    }
}
