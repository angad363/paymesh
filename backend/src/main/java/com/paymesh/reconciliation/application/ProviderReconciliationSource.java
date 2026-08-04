package com.paymesh.reconciliation.application;

import java.time.LocalDate;

/**
 * Where the provider's own truth for one day comes from.
 * <p>
 * A port because the transport is the one thing about reconciliation that is certain to differ per
 * provider: the simulator serves JSON over HTTP, a real acquirer drops a fixed-width file on SFTP at
 * 04:00, and a third posts a signed CSV. None of that changes what
 * {@link ReconcileProviderDayService} does with the rows, and putting a {@code RestClient} inside
 * that service would have welded the job to one of the three.
 */
@FunctionalInterface
public interface ProviderReconciliationSource {

    /**
     * @return the day's rows. An empty report is a legitimate answer -- a quiet day, or a date the
     *     provider has no record of -- and must not be reported as a failure.
     * @throws ProviderReportUnavailableException when the provider could not be asked or did not
     *     answer usefully. Distinct from an empty report ON PURPOSE: "the provider says nothing
     *     happened" and "we could not reach the provider" would otherwise both look like a clean
     *     reconciliation, and the second silently means no repairs happened today.
     */
    ProviderDayReport fetch(LocalDate date);
}
