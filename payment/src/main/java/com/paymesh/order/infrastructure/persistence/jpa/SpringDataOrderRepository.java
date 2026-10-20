package com.paymesh.order.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * The same row, locked until the caller's transaction ends: {@code SELECT ... FOR UPDATE}.
     * <p>
     * PESSIMISTIC_WRITE rather than optimistic {@code @Version}, and the difference matters here.
     * Optimistic control lets both writers proceed and fails the loser at flush time, which needs an
     * application-level retry to be correct; the lock makes the second reader WAIT, so it reads the
     * winner's committed result and judges itself against the truth. Payment's create path is
     * deciding whether an order may be collected against, and "wait and then see" is the only answer
     * that cannot be stale. SDD 23.3 names pessimistic locking for exactly this class of decision.
     * <p>
     * Still merchant-leading, like every other method here: a lock is not an authorization.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderJpaEntity o where o.merchantId = :merchantId and o.orderId = :orderId")
    Optional<OrderJpaEntity> findForUpdateByMerchantIdAndOrderId(
        @Param("merchantId") String merchantId,
        @Param("orderId") String orderId
    );

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

    /**
     * THE ONLY QUERY HERE WITHOUT A merchant_id PREDICATE, and the class javadoc's rule is suspended
     * for it deliberately rather than by omission.
     * <p>
     * The expiry sweeper has no tenant to scope by -- it runs on a timer, across every merchant, and
     * the merchant is DERIVED from each row it finds. That is the same asymmetry
     * {@code findForProviderCallbackForUpdate} has on the payment side, and it is safe for the same
     * reason: nothing is written here, and every write that follows is scoped by the merchant read
     * off the candidate row.
     * <p>
     * <b>Not locking, on purpose.</b> Locking a whole batch would hold rows across the entire sweep
     * and block merchants creating payment intents against orders the sweeper may not even touch.
     * The lock is taken per order, inside its own transaction, at the moment of the decision -- see
     * {@code ExpireOrdersService}.
     * <p>
     * Oldest deadline first so a backlog drains in the order it accumulated, and so a batch limit
     * cannot starve the orders that have been expired longest. Reads straight off
     * {@code idx_orders_expirable}, which is partial on exactly these two predicates.
     * <p>
     * IDENTIFIERS, NOT ENTITIES. The sweep re-reads every candidate under a lock anyway, so the
     * aggregate this used to build was mapped and discarded -- and mapped OUTSIDE the sweep's
     * per-item try/catch, where one unrehydratable row throws out of the whole run. Open item 2.
     */
    @Query("""
        select o.merchantId, o.orderId from OrderJpaEntity o
        where o.status = 'PENDING'
          and o.expiresAt is not null
          and o.expiresAt <= :now
        order by o.expiresAt asc
        """)
    List<Object[]> findExpirable(@Param("now") Instant now, Limit limit);
}
