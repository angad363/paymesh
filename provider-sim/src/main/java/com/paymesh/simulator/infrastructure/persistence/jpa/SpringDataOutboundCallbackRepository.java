package com.paymesh.simulator.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data's half of the outbound-callback adapter. Not referenced outside this package. */
public interface SpringDataOutboundCallbackRepository
    extends JpaRepository<OutboundCallbackJpaEntity, String> {

    /**
     * The candidate rows, unlocked.
     * <p>
     * Ordered by {@code deliverAfter} first, which is what makes the duplicate and out-of-order
     * pairs deterministic: their second row is enqueued a millisecond later precisely so this
     * ordering has something to sort on. {@code createdAt} and the id break remaining ties so the
     * query is stable rather than merely usually stable.
     * <p>
     * IDENTIFIERS, NOT ENTITIES: the dispatcher claims each row under a lock and used nothing else
     * off the candidate, so mapping here was discarded work done outside its per-item try/catch.
     * Open item 2.
     */
    @Query("""
        select c.outboundCallbackId from OutboundCallbackJpaEntity c
         where c.status = 'PENDING' and c.deliverAfter <= :now
         order by c.deliverAfter, c.createdAt, c.outboundCallbackId
        """)
    List<String> findDue(@Param("now") Instant now, org.springframework.data.domain.Pageable page);

    /**
     * Claims one row: {@code SELECT ... FOR UPDATE SKIP LOCKED} with the status re-checked.
     * <p>
     * {@code SKIP LOCKED} via the timeout hint of {@code -2}, which is Hibernate's encoding for it.
     * Empty therefore means either another dispatcher holds this row or it has already been
     * delivered since the candidate list was built -- both no-ops. Plain {@code FOR UPDATE} would
     * make a second dispatcher queue behind the first one's HTTP call instead.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        select c from OutboundCallbackJpaEntity c
         where c.outboundCallbackId = :id and c.status = 'PENDING'
        """)
    Optional<OutboundCallbackJpaEntity> findPendingForUpdate(@Param("id") String outboundCallbackId);

    List<OutboundCallbackJpaEntity> findByCallbackReferenceOrderByCreatedAtAsc(String callbackReference);
}
