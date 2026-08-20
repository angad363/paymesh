package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes the projected fact for one consumed event. Called from the {@code EventHandler}, so it runs
 * INSIDE the dispatcher's transaction and opens none of its own -- the inbox row and this write
 * commit together (the three {@code EventHandler} rules).
 *
 * <p>Idempotent through {@link ReportFactRepository#saveIfAbsent}: a redelivery finds the row and
 * does nothing. It only ever writes, and nothing here can affect the payment or settlement that
 * produced the event -- which is the whole reason Reporting is a consumer rather than a step in
 * anyone's transaction.
 *
 * <p>The {@code recordedAt} is stamped HERE, from the injected clock, rather than defaulted in the
 * migration. It is the value {@code asOf} reports, and a report's honesty about staleness should
 * not depend on a database default a future migration could quietly change.
 */
public final class RecordReportFactService {

    private final ReportFactRepository facts;
    private final Clock clock;

    public RecordReportFactService(ReportFactRepository facts, Clock clock) {
        this.facts = facts;
        this.clock = clock;
    }

    public void record(
        MerchantId merchantId,
        String sourceEventId,
        String eventType,
        String subjectId,
        String orderId,
        String currency,
        long amountMinor,
        Instant occurredAt
    ) {
        facts.saveIfAbsent(new ReportFact(
            sourceEventId,
            merchantId,
            eventType,
            subjectId,
            orderId,
            currency,
            amountMinor,
            occurredAt,
            Instant.now(clock)
        ));
    }
}
