package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.OutboundCallback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The port the simulator's callback queue answers. */
public interface OutboundCallbackRepository {

    OutboundCallback save(OutboundCallback callback);

    /**
     * The candidate rows: PENDING, due, oldest deadline first. <b>Read without a lock.</b>
     * <p>
     * Unlocked because this list is only a work plan. Locking the whole batch here would hold every
     * row in it for the duration of every HTTP call in it, so a batch of twenty would keep nineteen
     * locks alive while the first one waited on a socket. Each row is claimed individually by
     * {@link #findPendingForUpdate}, which is where the race is actually settled.
     */
    List<OutboundCallback> findDue(Instant now, int limit);

    /**
     * Claims one still-PENDING row for this transaction, or answers empty.
     * <p>
     * {@code FOR UPDATE SKIP LOCKED} plus the status predicate, which is a claim rather than a read:
     * empty means either another dispatcher holds the row (skipped rather than waited on) or it has
     * already been delivered since the candidate list was built. Both are no-ops, and neither is an
     * error -- which is exactly what makes a second dispatcher, or a test driving {@code dispatch()}
     * while the timer also runs, harmless.
     */
    Optional<OutboundCallback> findPendingForUpdate(String outboundCallbackId);

    /** Everything queued for one payment, oldest first. The support and reconciliation question. */
    List<OutboundCallback> findByCallbackReference(String callbackReference);
}
