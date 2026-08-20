package com.paymesh.reporting.infrastructure.persistence.jpa;

import com.paymesh.reporting.application.ReportExportRepository;
import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed {@link ReportExportRepository}. */
public final class JpaReportExportRepository implements ReportExportRepository {

    private final SpringDataReportExportRepository exports;

    public JpaReportExportRepository(SpringDataReportExportRepository exports) {
        this.exports = exports;
    }

    @Override
    public ReportExport save(ReportExport export) {
        return ReportJpaMapper.toDomain(
            exports.saveAndFlush(ReportJpaMapper.toEntity(export))
        );
    }

    @Override
    public Optional<ReportExport> findById(MerchantId merchantId, ReportExportId id) {
        return exports
            .findByReportExportIdAndMerchantId(id.value(), merchantId.value())
            .map(ReportJpaMapper::toDomain);
    }

    @Override
    public List<String> findPending(int limit) {
        return exports.findPendingIds(PageRequest.ofSize(limit));
    }

    @Override
    public Optional<ReportExport> claim(ReportExportId id) {
        return exports.findPendingForUpdate(id.value()).map(ReportJpaMapper::toDomain);
    }
}
