package com.paymesh.settlement.application;

/**
 * The ledger's contributions could not be read (timeout, refusal, 5xx, malformed body). ADR-047 §5:
 * the cut FAILS CLOSED per merchant -- no batch is cut on a read that was not got, and the next
 * tick retries. A distinct type so the sweeper can record a skip instead of a generic failure.
 */
public class LedgerReadUnavailableException extends RuntimeException {

    public LedgerReadUnavailableException(String message) {
        super(message);
    }

    public LedgerReadUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
