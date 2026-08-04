package com.paymesh.reconciliation.application;

import java.time.LocalDate;

/**
 * The provider could not be asked, or answered with something unusable.
 * <p>
 * Its whole reason to exist is to be distinguishable from an empty report. A day with no rows and a
 * day nobody could fetch look identical in every count the job produces -- zero examined, zero
 * repaired -- and treating them the same would let a provider that has been unreachable for a week
 * report a clean reconciliation every night.
 * <p>
 * HTTP-agnostic, like every other application-layer exception here: no status code, no
 * {@code ResponseStatusException}. The adapter that knows about HTTP is the one that throws it.
 */
public class ProviderReportUnavailableException extends RuntimeException {

    private final LocalDate date;

    public ProviderReportUnavailableException(LocalDate date, String reason, Throwable cause) {
        super("Could not read the provider's reconciliation report for " + date + ": " + reason, cause);
        this.date = date;
    }

    public LocalDate date() {
        return date;
    }
}
