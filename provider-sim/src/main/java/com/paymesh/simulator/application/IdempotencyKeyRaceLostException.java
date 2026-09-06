package com.paymesh.simulator.application;

/**
 * Two requests carrying one idempotency key raced, and this one lost on the unique constraint.
 * <p>
 * Thrown by the persistence adapter, caught by the service <b>outside</b> its transaction -- the
 * transaction is already dead by then and a re-read has to happen in a fresh one. The winner's row
 * is the answer, so the loser returns it and the caller cannot tell which of the two it was.
 * <p>
 * Never reaches the API. If it does, the service forgot to catch it, which is a 500 and correctly so.
 */
public final class IdempotencyKeyRaceLostException extends RuntimeException {

    private final transient String idempotencyKey;

    public IdempotencyKeyRaceLostException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " was claimed by a concurrent request");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
