package com.paymesh.audit.infrastructure.persistence.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data access to audit events. Not referenced outside this package.
 *
 * <p>There is no {@code save} override and no {@code delete}: the inherited {@code JpaRepository}
 * ones exist, but only {@code append} is ever called through the application port, and an UPDATE or
 * DELETE reaching the row is refused by the V36 trigger regardless of who issues it.
 */
public interface SpringDataAuditEventRepository
    extends JpaRepository<AuditEventJpaEntity, String> {

    /**
     * The read surface's filter, newest first. The string predicates are null-guarded, so an
     * all-null query returns the most recent events across the platform and any subset narrows
     * without a second method per combination. {@code auditEventId} breaks the {@code occurredAt}
     * tie so paging is stable.
     *
     * <h2>THE TIME BOUND IS ALWAYS APPLIED, NEVER NULL-GUARDED</h2>
     *
     * {@code (:from is null or ...)} sends PostgreSQL an untyped NULL it cannot assign a type to --
     * "could not determine data type of parameter" -- because, unlike the string params, a timestamp
     * null has no adjacent column to borrow a type from. So the caller always passes a bound: the
     * requested window, or a floor/ceiling that spans everything ({@code JpaAuditEventRepository}).
     * The range is then unconditional and both params are typed by the comparison to {@code
     * occurredAt}.
     */
    @Query("""
        select e from AuditEventJpaEntity e
         where (:merchantId is null or e.merchantId = :merchantId)
           and (:action is null or e.action = :action)
           and (:actorId is null or e.actorId = :actorId)
           and e.occurredAt >= :from
           and e.occurredAt < :to
         order by e.occurredAt desc, e.auditEventId desc
        """)
    List<AuditEventJpaEntity> search(
        @Param("merchantId") String merchantId,
        @Param("action") String action,
        @Param("actorId") String actorId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable page
    );

    /**
     * The generator's source: every event in a window, oldest first, optionally one tenant's. The
     * window is required here (an export always has one); the merchant filter is null-guarded.
     */
    @Query("""
        select e from AuditEventJpaEntity e
         where e.occurredAt >= :from and e.occurredAt < :to
           and (:merchantId is null or e.merchantId = :merchantId)
         order by e.occurredAt asc, e.auditEventId asc
        """)
    List<AuditEventJpaEntity> findInWindow(
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("merchantId") String merchantId,
        Pageable page
    );
}
