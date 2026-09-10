package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** How the application reaches {@code report_facts}. Implemented in infrastructure. */
public interface ReportFactRepository {

    /**
     * Writes the fact unless its source event already produced one.
     *
     * @return false when the event had already been projected, which is a no-op and not an error --
     *     at-least-once delivery makes a redelivery normal
     */
    boolean saveIfAbsent(ReportFact fact);

    /**
     * The GROUP BY both reports are assembled from: one cell per (currency, UTC day, event type).
     *
     * <p>Merchant-scoped, always. There is no unscoped overload and there must not be one -- a
     * reporting query is exactly the shape that leaks another tenant's totals if the scope is ever
     * forgotten, and the leak reads as a plausible number rather than an error.
     */
    List<FactTally> tallyDaily(MerchantId merchantId, Set<String> eventTypes, ReportWindow window);

    /**
     * The newest fact this merchant's projection holds, by REPORTING's clock.
     *
     * @return empty when the merchant has no facts at all, which is an honest "nothing has been
     *     projected yet" rather than a timestamp that would imply the projection is current
     */
    Optional<Instant> latestRecordedAt(MerchantId merchantId);

    /**
     * The export's rows, oldest first.
     *
     * @param limit a hard cap, so one export cannot pull an unbounded result set into a String
     */
    List<ReportFact> findInWindow(MerchantId merchantId, ReportWindow window, int limit);
}
