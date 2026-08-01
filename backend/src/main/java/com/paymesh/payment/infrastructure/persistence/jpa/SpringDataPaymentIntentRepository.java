package com.paymesh.payment.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data access to the payment_intents table.
 * <p>
 * findById(String) is inherited from JpaRepository but is NOT used by the adapter and must not be:
 * it resolves an intent by id alone, with no tenant predicate. Every method declared here names
 * merchantId first, so the generated SQL always carries "where merchant_id = ?".
 */
public interface SpringDataPaymentIntentRepository extends JpaRepository<PaymentIntentJpaEntity, String> {

    Optional<PaymentIntentJpaEntity> findByMerchantIdAndPaymentIntentId(
        String merchantId, String paymentIntentId
    );

    /**
     * Whether the order already holds an intent occupying its slot. The excluded statuses are the
     * ones that release it, and they are supplied by the caller so this method and
     * {@code uq_payment_intents_live_per_order} cannot drift apart silently -- the adapter holds
     * the single list.
     */
    boolean existsByMerchantIdAndOrderIdAndStatusNotIn(
        String merchantId, String orderId, Collection<String> releasedStatuses
    );

    /**
     * KEYSET PAGINATION, AND THE TIEBREAK IS LOAD-BEARING.
     * <p>
     * The predicate and the ORDER BY both compare the PAIR {@code (created_at, payment_intent_id)}.
     * Ordering by created_at alone is not a total order -- two intents created in the same instant
     * have no defined position relative to each other -- so a page boundary that falls between them
     * either skips a row (a strict {@code <} walks past every row sharing the boundary instant) or
     * repeats the page ({@code <=}). payment_intent_id is unique, so the pair always is.
     * <p>
     * idx_payment_intents_merchant_created_at is declared over the same three columns in the same
     * directions, so this reads straight off the index with no sort step.
     */
    @Query("""
        select p from PaymentIntentJpaEntity p
        where p.merchantId = :merchantId
          and (p.createdAt < :cursorCreatedAt
               or (p.createdAt = :cursorCreatedAt and p.paymentIntentId < :cursorPaymentIntentId))
        order by p.createdAt desc, p.paymentIntentId desc
        """)
    List<PaymentIntentJpaEntity> findPage(
        @Param("merchantId") String merchantId,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorPaymentIntentId") String cursorPaymentIntentId,
        Limit limit
    );

    /**
     * The same page, filtered. The three filtered variants are written out rather than folded into
     * one query with nullable parameters: a bind parameter compared against NULL leaves PostgreSQL
     * unable to infer the parameter's type, and the workaround (an explicit CAST in JPQL) is harder
     * to read than the duplicated predicate. Same trade {@code SpringDataOrderRepository} made.
     */
    @Query("""
        select p from PaymentIntentJpaEntity p
        where p.merchantId = :merchantId
          and p.status = :status
          and (p.createdAt < :cursorCreatedAt
               or (p.createdAt = :cursorCreatedAt and p.paymentIntentId < :cursorPaymentIntentId))
        order by p.createdAt desc, p.paymentIntentId desc
        """)
    List<PaymentIntentJpaEntity> findPageByStatus(
        @Param("merchantId") String merchantId,
        @Param("status") String status,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorPaymentIntentId") String cursorPaymentIntentId,
        Limit limit
    );

    @Query("""
        select p from PaymentIntentJpaEntity p
        where p.merchantId = :merchantId
          and p.orderId = :orderId
          and (p.createdAt < :cursorCreatedAt
               or (p.createdAt = :cursorCreatedAt and p.paymentIntentId < :cursorPaymentIntentId))
        order by p.createdAt desc, p.paymentIntentId desc
        """)
    List<PaymentIntentJpaEntity> findPageByOrder(
        @Param("merchantId") String merchantId,
        @Param("orderId") String orderId,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorPaymentIntentId") String cursorPaymentIntentId,
        Limit limit
    );

    @Query("""
        select p from PaymentIntentJpaEntity p
        where p.merchantId = :merchantId
          and p.status = :status
          and p.orderId = :orderId
          and (p.createdAt < :cursorCreatedAt
               or (p.createdAt = :cursorCreatedAt and p.paymentIntentId < :cursorPaymentIntentId))
        order by p.createdAt desc, p.paymentIntentId desc
        """)
    List<PaymentIntentJpaEntity> findPageByStatusAndOrder(
        @Param("merchantId") String merchantId,
        @Param("status") String status,
        @Param("orderId") String orderId,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorPaymentIntentId") String cursorPaymentIntentId,
        Limit limit
    );
}
