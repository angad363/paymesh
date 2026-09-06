package com.paymesh.shared.idempotency.application;

import com.paymesh.shared.idempotency.domain.IdempotencyRecord;
import com.paymesh.shared.idempotency.domain.IdempotencyStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

/**
 * Durable memory of which writes have already been attempted.
 * <p>
 * Every method here commits on its own. That is not an implementation detail: the whole concurrency
 * story rests on {@link #insertIfAbsent} having committed <em>before</em> the handler runs, so a
 * simultaneous retry can see it.
 */
public interface IdempotencyRepository {

    /**
     * Insert an {@link IdempotencyStatus#IN_PROGRESS} record, or report that the key is taken.
     * <p>
     * This must be a single statement that lets the database decide the winner on the primary key.
     * A read followed by a write is not the same thing and is the bug this interface exists to
     * prevent: two requests can both read "absent" and both go on to move money.
     *
     * @return true when this caller inserted the row and therefore owns the request
     */
    boolean insertIfAbsent(IdempotencyRecord record);

    Optional<IdempotencyRecord> findBy(MerchantId merchantId, String endpoint, String idempotencyKey);

    /** Store the response so a later retry replays it instead of re-running the handler. */
    void complete(IdempotencyRecord record);

    /** Forget the attempt entirely, so a retry is a real retry. Used only on 5xx (ADR-009). */
    void delete(MerchantId merchantId, String endpoint, String idempotencyKey);
}
