package com.paymesh.shared.idempotency.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Spring Data access to idempotency_records.
 * <p>
 * The three writes are hand-written single statements rather than {@code save()}. That is the whole
 * design: {@code save()} on an entity with an assigned id issues a SELECT and then an INSERT or an
 * UPDATE, which is a read-then-write and would let two concurrent retries both decide the key was
 * free. {@code INSERT ... ON CONFLICT DO NOTHING} pushes the decision into the database, where it
 * is atomic.
 * <p>
 * Each method is its own transaction. The filter calls them from outside any transaction, so
 * {@code insertIfAbsent} has committed by the time it returns -- which is what makes it visible to
 * the simultaneous retry that must lose.
 */
public interface SpringDataIdempotencyRepository
    extends JpaRepository<IdempotencyRecordJpaEntity, IdempotencyRecordJpaId> {

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO idempotency_records
            (merchant_id, endpoint, idempotency_key, request_hash, status, created_at)
        VALUES (:merchantId, :endpoint, :idempotencyKey, :requestHash, 'IN_PROGRESS', :createdAt)
        ON CONFLICT ON CONSTRAINT pk_idempotency_records DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("merchantId") String merchantId,
        @Param("endpoint") String endpoint,
        @Param("idempotencyKey") String idempotencyKey,
        @Param("requestHash") String requestHash,
        @Param("createdAt") Instant createdAt
    );

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE idempotency_records
           SET status = 'COMPLETED',
               response_status = :responseStatus,
               response_body = :responseBody,
               completed_at = :completedAt
         WHERE merchant_id = :merchantId
           AND endpoint = :endpoint
           AND idempotency_key = :idempotencyKey
        """, nativeQuery = true)
    int complete(
        @Param("merchantId") String merchantId,
        @Param("endpoint") String endpoint,
        @Param("idempotencyKey") String idempotencyKey,
        @Param("responseStatus") Short responseStatus,
        @Param("responseBody") String responseBody,
        @Param("completedAt") Instant completedAt
    );

    @Modifying
    @Transactional
    @Query(value = """
        DELETE FROM idempotency_records
         WHERE merchant_id = :merchantId
           AND endpoint = :endpoint
           AND idempotency_key = :idempotencyKey
        """, nativeQuery = true)
    int deleteRecord(
        @Param("merchantId") String merchantId,
        @Param("endpoint") String endpoint,
        @Param("idempotencyKey") String idempotencyKey
    );
}
