package com.paymesh.reporting.infrastructure.persistence.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Spring Data access to report facts. Not referenced outside this package. */
public interface SpringDataReportFactRepository extends JpaRepository<ReportFactJpaEntity, String> {

    /**
     * The GROUP BY behind both reports: one row per (currency, UTC day, event type).
     *
     * <h2>NATIVE, BECAUSE OF ONE FUNCTION</h2>
     *
     * JPQL has no portable way to truncate a timestamp to a calendar day, and the alternatives are
     * worse than a native query: fetching every fact and bucketing in Java turns a report into a
     * full read of the merchant's history, and a Hibernate {@code function()} escape is a native
     * call wearing JPQL's clothes.
     *
     * <h2>{@code AT TIME ZONE 'UTC'} IS LOAD-BEARING</h2>
     *
     * {@code occurred_at} is {@code TIMESTAMPTZ}; casting it to a date without naming a zone uses
     * the SESSION's {@code TimeZone}, so the same payment would fall on different days depending on
     * where the application happened to be deployed -- and the report would change without the data
     * changing. Every timestamp in this codebase is UTC, so the buckets are too.
     *
     * <p>Returns {@code Object[]} rather than a projection interface, matching the convention the
     * outbox repository set. Column order is the contract; {@link JpaReportFactRepository} maps it.
     */
    @Query(
        value = """
            select f.currency,
                   (f.occurred_at at time zone 'UTC')::date as day,
                   f.event_type,
                   count(*),
                   coalesce(sum(f.amount_minor), 0)
              from report_facts f
             where f.merchant_id = :merchantId
               and f.event_type in (:eventTypes)
               and f.occurred_at >= :from
               and f.occurred_at < :to
             group by f.currency, day, f.event_type
             order by f.currency, day, f.event_type
            """,
        nativeQuery = true
    )
    List<Object[]> tallyDaily(
        @Param("merchantId") String merchantId,
        @Param("eventTypes") Collection<String> eventTypes,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    /**
     * How far this merchant's projection has caught up. Backed by
     * {@code idx_report_facts_merchant_recorded}, because it runs on every report response and a
     * scan of the merchant's whole history would make the freshness signal the slowest part of the
     * report carrying it.
     *
     * @return null when the merchant has no facts, which the service turns into a null {@code asOf}
     */
    @Query("select max(f.recordedAt) from ReportFactJpaEntity f where f.merchantId = :merchantId")
    Instant latestRecordedAt(@Param("merchantId") String merchantId);

    /** The export's rows: one merchant's facts in a window, oldest first. */
    @Query("""
        select f from ReportFactJpaEntity f
         where f.merchantId = :merchantId
           and f.occurredAt >= :from
           and f.occurredAt < :to
         order by f.occurredAt, f.sourceEventId
        """)
    List<ReportFactJpaEntity> findInWindow(
        @Param("merchantId") String merchantId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable page
    );
}
