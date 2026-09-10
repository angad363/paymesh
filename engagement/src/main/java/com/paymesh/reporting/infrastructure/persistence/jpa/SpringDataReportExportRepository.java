package com.paymesh.reporting.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Spring Data access to report exports. Not referenced outside this package. */
public interface SpringDataReportExportRepository
    extends JpaRepository<ReportExportJpaEntity, String> {

    /**
     * MERCHANT-SCOPED BY THE QUERY, not by a check after the fact. A {@code findById} followed by an
     * ownership comparison is one forgotten line away from a cross-tenant read; putting the tenant
     * in the WHERE means another merchant's id returns empty and the caller gets the same 404 as for
     * an id that never existed.
     */
    Optional<ReportExportJpaEntity> findByReportExportIdAndMerchantId(
        String reportExportId, String merchantId
    );

    /**
     * The generator's candidate list: PENDING ids, oldest first. Backed by the partial index
     * {@code idx_report_exports_pending}; terminal rows accumulate forever and are never wanted here.
     */
    @Query("""
        select e.reportExportId from ReportExportJpaEntity e
         where e.status = 'PENDING'
         order by e.requestedAt, e.reportExportId
        """)
    List<String> findPendingIds(Pageable page);

    /**
     * Claims one row: {@code SELECT ... FOR UPDATE SKIP LOCKED}, status re-checked. {@code SKIP
     * LOCKED} via Hibernate's {@code -2} timeout. Empty means another generator holds it or it is no
     * longer PENDING -- both no-ops.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        select e from ReportExportJpaEntity e
         where e.reportExportId = :id and e.status = 'PENDING'
        """)
    Optional<ReportExportJpaEntity> findPendingForUpdate(@Param("id") String reportExportId);
}
