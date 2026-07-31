package com.paymesh.order.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data access to the orders table.
 * <p>
 * findById(String) is inherited from JpaRepository but is NOT used by the adapter and must not be:
 * it resolves an order by id alone, with no tenant predicate. Every method declared here names
 * merchantId first, so the generated SQL always carries "where merchant_id = ?".
 */
public interface SpringDataOrderRepository extends JpaRepository<OrderJpaEntity, String> {

    Optional<OrderJpaEntity> findByMerchantIdAndOrderId(String merchantId, String orderId);

    boolean existsByMerchantIdAndMerchantOrderReference(String merchantId, String merchantOrderReference);

    /**
     * KEYSET PAGINATION, AND THE TIEBREAK IS LOAD-BEARING.
     * <p>
     * The predicate and the ORDER BY both compare the PAIR {@code (created_at, order_id)}. Ordering
     * by created_at alone is not a total order -- two orders created in the same instant have no
     * defined position relative to each other -- so a page boundary that falls between them either
     * skips a row (a strict {@code <} walks past every row sharing the boundary instant) or repeats
     * the page ({@code <=}). order_id is unique, so the pair always is a total order.
     * <p>
     * idx_orders_merchant_created_at is declared over the same three columns in the same directions,
     * so this reads straight off the index with no sort step.
     */
    @Query("""
        select o from OrderJpaEntity o
        where o.merchantId = :merchantId
          and (o.createdAt < :cursorCreatedAt
               or (o.createdAt = :cursorCreatedAt and o.orderId < :cursorOrderId))
        order by o.createdAt desc, o.orderId desc
        """)
    List<OrderJpaEntity> findPage(
        @Param("merchantId") String merchantId,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorOrderId") String cursorOrderId,
        Limit limit
    );

    /**
     * The same page, filtered. Written out rather than folded into one query with a nullable
     * {@code :status}: a bind parameter compared against NULL leaves PostgreSQL unable to infer the
     * parameter's type, and the workaround (an explicit CAST in JPQL) is harder to read than the
     * duplicated predicate.
     */
    @Query("""
        select o from OrderJpaEntity o
        where o.merchantId = :merchantId
          and o.status = :status
          and (o.createdAt < :cursorCreatedAt
               or (o.createdAt = :cursorCreatedAt and o.orderId < :cursorOrderId))
        order by o.createdAt desc, o.orderId desc
        """)
    List<OrderJpaEntity> findPageByStatus(
        @Param("merchantId") String merchantId,
        @Param("status") String status,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorOrderId") String cursorOrderId,
        Limit limit
    );
}
