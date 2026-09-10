package com.paymesh.reporting.infrastructure.persistence.jpa;

import com.paymesh.reporting.application.FactTally;
import com.paymesh.reporting.application.ReportFactRepository;
import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL-backed {@link ReportFactRepository}. */
public final class JpaReportFactRepository implements ReportFactRepository {

    private final SpringDataReportFactRepository facts;

    public JpaReportFactRepository(SpringDataReportFactRepository facts) {
        this.facts = facts;
    }

    /**
     * Check-then-insert, and the check is not the guard -- {@code pk_report_facts} is. This runs
     * inside the event dispatcher's transaction, which the inbox has already serialized per
     * (consumer, event), so the two callers that could race here cannot both run. If that ever
     * changes the primary key throws, the handler rolls back, and the retry finds the row.
     */
    @Override
    public boolean saveIfAbsent(ReportFact fact) {
        if (facts.existsById(fact.sourceEventId())) {
            return false;
        }

        facts.saveAndFlush(ReportJpaMapper.toEntity(fact));

        return true;
    }

    @Override
    public List<FactTally> tallyDaily(
        MerchantId merchantId, Set<String> eventTypes, ReportWindow window
    ) {
        // An empty IN list is not valid SQL in PostgreSQL, and no caller passes one -- but a report
        // over no event types is trivially empty, so answering it here is cheaper than a query that
        // would fail at the driver.
        if (eventTypes.isEmpty()) {
            return List.of();
        }

        return facts
            .tallyDaily(merchantId.value(), eventTypes, window.from(), window.to())
            .stream()
            .map(ReportJpaMapper::toTally)
            .toList();
    }

    @Override
    public Optional<Instant> latestRecordedAt(MerchantId merchantId) {
        return Optional.ofNullable(facts.latestRecordedAt(merchantId.value()));
    }

    @Override
    public List<ReportFact> findInWindow(MerchantId merchantId, ReportWindow window, int limit) {
        return facts
            .findInWindow(
                merchantId.value(), window.from(), window.to(), PageRequest.ofSize(limit)
            )
            .stream()
            .map(ReportJpaMapper::toDomain)
            .toList();
    }
}
