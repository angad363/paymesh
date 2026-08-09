package com.paymesh.payment.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * The same row, locked until the caller's transaction ends: {@code SELECT ... FOR UPDATE}.
     * <p>
     * PESSIMISTIC RATHER THAN OPTIMISTIC, and SDD 23.3 lists exactly this class of decision for it.
     * Optimistic control lets both writers proceed and fails the loser at flush time with a message
     * about row counts; the lock makes the loser WAIT, read the winner's result, and be refused by
     * the state machine instead. Both prevent the double write. Only one of them can explain itself.
     * <p>
     * Still merchant-leading, like every other method here: a lock is not an authorization.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p from PaymentIntentJpaEntity p
        where p.merchantId = :merchantId and p.paymentIntentId = :paymentIntentId
        """)
    Optional<PaymentIntentJpaEntity> findForUpdateByMerchantIdAndPaymentIntentId(
        @Param("merchantId") String merchantId,
        @Param("paymentIntentId") String paymentIntentId
    );

    /**
     * The same lock, with NO MERCHANT PREDICATE. The one query in this interface without one.
     * <p>
     * A provider callback arrives on a shared-secret endpoint with no bearer token, names an intent,
     * and the merchant is DERIVED from the row it finds -- the same asymmetry that makes
     * {@code pk_provider_callbacks} not merchant-leading. Requiring a merchant here would mean
     * taking one from the caller, and a caller-supplied tenant on an endpoint that moves payments to
     * SUCCEEDED is exactly what ADR-007 exists to prevent. The derived merchant is then what every
     * write in the callback transaction scopes by.
     * <p>
     * Named so a merchant-facing caller cannot reach for it by mistake. Nothing but the callback
     * path may use it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p from PaymentIntentJpaEntity p where p.paymentIntentId = :paymentIntentId
        """)
    Optional<PaymentIntentJpaEntity> findForProviderCallbackForUpdate(
        @Param("paymentIntentId") String paymentIntentId
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

    /**
     * THE SECOND QUERY HERE WITHOUT A merchant_id PREDICATE, and like the first it is deliberate
     * rather than forgotten.
     * <p>
     * The PROCESSING timeout runs on a timer with no token and no tenant, across every merchant, and
     * derives the merchant from each row it finds. Nothing is written here; every write that follows
     * is scoped by that derived merchant.
     * <p>
     * <b>Not locking, on purpose.</b> Locking a whole batch would hold rows for the length of the
     * sweep and block provider callbacks for intents this sweep may not even touch -- and a blocked
     * callback is the very thing the timeout exists to compensate for. The lock is taken per intent,
     * inside its own transaction, at the moment of the decision.
     * <p>
     * Longest-stranded first so a backlog drains in the order it accumulated. Reads straight off
     * {@code idx_payment_intents_processing_since}, which is partial on exactly this status.
     * <p>
     * IDENTIFIERS, NOT ENTITIES, for the reason {@code findExpirable} carries: the sweep re-reads
     * each candidate under a lock, so mapping here was discarded work done outside the per-item
     * try/catch, where one unrehydratable row kills the run. Open item 2.
     */
    @Query("""
        select p.paymentIntentId from PaymentIntentJpaEntity p
        where p.status = 'PROCESSING'
          and p.updatedAt <= :confirmedBefore
        order by p.updatedAt asc
        """)
    List<String> findStrandedInProcessing(
        @Param("confirmedBefore") Instant confirmedBefore,
        Limit limit
    );

    /**
     * Checkouts nobody came back to. Oldest first, so a backlog drains in the order it accumulated.
     * <p>
     * The status list is exactly the two states that precede a provider ever being told anything, so
     * a cancellation here cannot contradict a payment that happened. It deliberately stops short of
     * PROCESSING, which ADR-015 owns and which cannot be cancelled at all.
     * <p>
     * IDENTIFIERS, NOT ENTITIES, for the reason {@code findStrandedInProcessing} carries: the cancel
     * re-reads the intent under a lock, so mapping here was discarded work done outside the sweep's
     * per-item try/catch, where one unrehydratable row kills the run. Open item 2.
     */
    @Query("""
        select p.merchantId, p.paymentIntentId from PaymentIntentJpaEntity p
        where p.status in ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_CONFIRMATION')
          and p.updatedAt <= :untouchedBefore
        order by p.updatedAt asc
        """)
    List<Object[]> findAbandonedBeforeConfirmation(
        @Param("untouchedBefore") Instant untouchedBefore,
        Limit limit
    );

    /**
     * Risk's velocity count (ADR-030).
     * <p>
     * EXCLUDES THE INTENT BEING JUDGED. It was created inside this same window, so a plain count
     * always returns the subject of the question and every threshold fires one confirm early -- the
     * classic off-by-one in a velocity feature, and one no unit test can see because the stub for
     * this port returns a hand-set number.
     * <p>
     * Reads off {@code idx_payment_intents_merchant_customer_created} (V27).
     */
    @Query("""
        select count(p) from PaymentIntentJpaEntity p
        where p.merchantId = :merchantId
          and p.customerId = :customerId
          and p.createdAt >= :createdAfter
          and p.paymentIntentId <> :excluding
        """)
    long countForCustomerSince(
        @Param("merchantId") String merchantId,
        @Param("customerId") String customerId,
        @Param("createdAfter") Instant createdAfter,
        @Param("excluding") String excluding
    );
}
