package com.paymesh.ledger.application;

/**
 * This money has already been posted under this idempotency key.
 * <p>
 * Not an error condition in the ordinary sense -- it is the ledger refusing to record the same
 * movement twice, which is the outcome the unique index exists to produce. The posting service
 * catches it and returns the journal that already exists.
 */
public final class LedgerTransactionAlreadyPostedException extends RuntimeException {

    private final String idempotencyKey;

    public LedgerTransactionAlreadyPostedException(String idempotencyKey) {
        super("A ledger transaction is already posted under " + idempotencyKey);

        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
