package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Appends a domain event to the outbox.
 * <p>
 * <strong>It assumes a transaction is already open and must never start one.</strong> The whole
 * value of the outbox is that the event and the state change it describes commit together; an
 * implementation that opened its own transaction -- or worse, ran REQUIRES_NEW -- would commit the
 * event independently and reintroduce exactly the window this pattern removes. The next reader's
 * instinct is to make this self-contained. Do not.
 * <p>
 * The caller opens the transaction, visibly, with a {@code TransactionTemplate} (ADR-010).
 */
public interface OutboxWriter {

    void append(OutboxEvent event);
}
