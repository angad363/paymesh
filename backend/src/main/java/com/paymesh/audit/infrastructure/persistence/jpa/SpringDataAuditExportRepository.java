package com.paymesh.audit.infrastructure.persistence.jpa;

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

/** Spring Data access to audit exports. Copied from the report-export repository. */
public interface SpringDataAuditExportRepository
    extends JpaRepository<AuditExportJpaEntity, String> {

    /**
     * The generator's candidate list: PENDING ids, oldest first. Backed by the partial index
     * {@code idx_audit_exports_pending}; terminal rows accumulate forever and are never wanted here.
     */
    @Query("""
        select e.auditExportId from AuditExportJpaEntity e
         where e.status = 'PENDING'
         order by e.requestedAt, e.auditExportId
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
        select e from AuditExportJpaEntity e
         where e.auditExportId = :id and e.status = 'PENDING'
        """)
    Optional<AuditExportJpaEntity> findPendingForUpdate(@Param("id") String auditExportId);
}
