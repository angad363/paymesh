package com.paymesh.refund.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataRefundRepository extends JpaRepository<RefundJpaEntity, String> {

    Optional<RefundJpaEntity> findByMerchantIdAndRefundId(String merchantId, String refundId);

    /**
     * Row-locked, and NOT merchant-scoped -- deliberately.
     * <p>
     * The callback path has no merchant: a provider is authenticated by a shared secret, not as a
     * tenant, and the refund id is what it names. Scoping this query would need a merchant the
     * caller cannot supply. The merchant-facing paths take the lock only after a scoped read has
     * already proved the refund is theirs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundJpaEntity r where r.refundId = :refundId")
    Optional<RefundJpaEntity> findForUpdate(@Param("refundId") String refundId);

    @Query("""
        select r from RefundJpaEntity r
        where r.merchantId = :merchantId
          and (r.createdAt < :cursorCreatedAt
               or (r.createdAt = :cursorCreatedAt and r.refundId < :cursorRefundId))
        order by r.createdAt desc, r.refundId desc
        """)
    List<RefundJpaEntity> findPage(
        @Param("merchantId") String merchantId,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorRefundId") String cursorRefundId,
        Limit limit
    );

    /**
     * What is already spoken for against one payment. Mirrors
     * {@code tr_refunds_within_captured}'s {@code NOT IN ('FAILED','CANCELLED')} exactly -- if the
     * two ever disagree the trigger wins, and the merchant gets a constraint violation instead of a
     * sentence.
     */
    /** Oldest first, so a backlog drains in the order it accumulated. */
    @Query("""
        select r from RefundJpaEntity r
        where r.status = 'PROCESSING' and r.createdAt < :threshold
        order by r.createdAt asc
        """)
    List<RefundJpaEntity> findProcessingOlderThan(
        @Param("threshold") Instant threshold,
        Limit limit
    );

    @Query("""
        select coalesce(sum(r.amountMinor), 0) from RefundJpaEntity r
        where r.paymentIntentId = :paymentIntentId
          and r.status not in ('FAILED', 'CANCELLED')
        """)
    long activeTotalMinor(@Param("paymentIntentId") String paymentIntentId);
}
