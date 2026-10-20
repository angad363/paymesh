package com.paymesh.payment.application;

import com.paymesh.payment.domain.ProviderCallback;

/**
 * Append-only writes to the provider callback log.
 * <p>
 * One method, and no read: nothing queries this table yet. Reconciliation will, and inventing the
 * query before the job exists would be a guess about what it wants.
 * <p>
 * Like {@code OutboxWriter}, {@code insert} assumes a transaction is already open and must not start
 * one. <b>That is the whole mechanism</b>: a duplicate loses on {@code pk_provider_callbacks} and
 * takes the accompanying state change down with it. An implementation that committed the row
 * separately would swallow the provider's event permanently the first time the transition failed,
 * leaving the payment in PROCESSING with no way to replay it (ADR-012).
 */
public interface ProviderCallbackRepository {

    /**
     * Records the delivery, or refuses it as a duplicate.
     *
     * @throws DuplicateProviderCallbackException when this provider has already delivered this event
     *                                            id. The caller must let it escape the transaction.
     */
    void insert(ProviderCallback callback);
}
