package com.paymesh.settlement.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * The payout callback dedup table. No entity, because nothing ever reads a row back -- the INSERT's
 * own row count is the entire answer.
 */
public interface SpringDataPayoutCallbackRepository
    extends Repository<PayoutJpaEntity, String> {

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING}, native because the clause is PostgreSQL's.
     * <p>
     * <b>The row count IS the dedup decision.</b> Reading first and then inserting would leave a
     * window in which two deliveries of one event both find nothing and both apply -- the same
     * mistake {@code processed_events} and the idempotency filter were each built to avoid, and the
     * one this codebase has proved by sabotage twice.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO payout_callbacks (
                provider, external_event_id, payout_id, outcome,
                payload_hash, occurred_at, received_at
            )
            VALUES (
                :provider, :externalEventId, :payoutId, :outcome,
                :payloadHash, :occurredAt, :receivedAt
            )
            ON CONFLICT (provider, external_event_id) DO NOTHING
            """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("provider") String provider,
        @Param("externalEventId") String externalEventId,
        @Param("payoutId") String payoutId,
        @Param("outcome") String outcome,
        @Param("payloadHash") String payloadHash,
        @Param("occurredAt") Instant occurredAt,
        @Param("receivedAt") Instant receivedAt
    );
}
