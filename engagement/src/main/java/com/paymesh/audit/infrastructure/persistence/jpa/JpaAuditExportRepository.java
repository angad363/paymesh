package com.paymesh.audit.infrastructure.persistence.jpa;

import com.paymesh.audit.application.AuditExportRepository;
import com.paymesh.audit.domain.AuditExport;
import com.paymesh.audit.domain.AuditExportId;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed {@link AuditExportRepository}. */
public final class JpaAuditExportRepository implements AuditExportRepository {

    private final SpringDataAuditExportRepository exports;

    public JpaAuditExportRepository(SpringDataAuditExportRepository exports) {
        this.exports = exports;
    }

    @Override
    public AuditExport save(AuditExport export) {
        return AuditJpaMapper.toDomain(
            exports.saveAndFlush(AuditJpaMapper.toEntity(export))
        );
    }

    @Override
    public Optional<AuditExport> findById(AuditExportId id) {
        return exports.findById(id.value()).map(AuditJpaMapper::toDomain);
    }

    @Override
    public List<String> findPending(int limit) {
        return exports.findPendingIds(PageRequest.ofSize(limit));
    }

    @Override
    public Optional<AuditExport> claim(AuditExportId id) {
        return exports.findPendingForUpdate(id.value()).map(AuditJpaMapper::toDomain);
    }
}
