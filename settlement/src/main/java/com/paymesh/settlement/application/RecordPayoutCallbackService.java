package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.PayoutId;
import com.paymesh.settlement.domain.PayoutOutcome;
import com.paymesh.settlement.domain.SettlementBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * The provider's answer about a payout. SDD 17.2, ADR-032.
 *
 * <h2>THIS IS THE ONLY THING THAT CAN SAY MONEY LEFT THE PLATFORM</h2>
 *
 * {@code payout.paid} credits {@code BANK_CASH}, and nothing else in this codebase does. So the
 * journal is posted from a signed callback and never from PayMesh's own submission -- the same rule
 * Payment follows for a capture and Refund for a reversal, applied at the one boundary where the
 * money is PayMesh's rather than a merchant's.
 *
 * <h2>Deduplicated by the primary key, not by a read</h2>
 *
 * {@code pk_payout_callbacks} on (provider, external event id). The insert either wins or does
 * nothing, and the row count is the answer, so two deliveries of one event cannot both pass a check
 * and both apply. Same mechanism as {@code processed_events} and {@code provider_callbacks}.
 */
public final class RecordPayoutCallbackService {

    private static final Logger log = LoggerFactory.getLogger(RecordPayoutCallbackService.class);

    private final PayoutRepository payouts;
    private final SettlementBatchRepository batches;
    private final PayoutCallbackRepository callbacks;
    private final CompleteSettlementService completeSettlement;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RecordPayoutCallbackService(
        PayoutRepository payouts,
        SettlementBatchRepository batches,
        PayoutCallbackRepository callbacks,
        CompleteSettlementService completeSettlement,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.payouts = payouts;
        this.batches = batches;
        this.callbacks = callbacks;
        this.completeSettlement = completeSettlement;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Outcome record(
        String provider,
        String externalEventId,
        String rawPayoutId,
        PayoutOutcome outcome,
        String failureReason,
        Instant occurredAt,
        String payloadHash
    ) {
        PayoutId payoutId = PayoutId.from(rawPayoutId);

        return transactions.execute(status -> {
            Payout payout = payouts.findForUpdate(payoutId).orElse(null);

            if (payout == null) {
                // A 404 rather than a silent success. ADR-012 §7's reasoning applies unchanged: the
                // likeliest cause is a callback overtaking the transaction that created the row, so
                // the provider should retry rather than be told everything is fine.
                return Outcome.UNKNOWN_PAYOUT;
            }

            boolean isNew = callbacks.recordIfNew(
                provider, externalEventId, payoutId, outcome, payloadHash, occurredAt,
                Instant.now(clock)
            );

            if (!isNew) {
                return Outcome.DUPLICATE;
            }

            if (payout.isTerminal()) {
                // The payout was answered already -- by an earlier callback, or by its submission
                // budget running out. Recorded above so the disagreement is visible, applied to
                // nothing, because a terminal payout's funds have already gone one way or the other.
                log.info(
                    "Payout {} is already {}; callback {} recorded and not applied",
                    payoutId.value(), payout.status(), externalEventId
                );

                return Outcome.ALREADY_TERMINAL;
            }

            SettlementBatch batch = batches
                .find(payout.merchantId(), payout.settlementBatchId())
                .orElseThrow(() -> new IllegalStateException(
                    "Payout " + payoutId.value() + " has no batch; fk_payouts_batch says otherwise"
                ));

            if (outcome == PayoutOutcome.SUCCEEDED) {
                completeSettlement.markPaid(payout, batch);
            } else {
                completeSettlement.returnFunds(
                    payout, batch, failureReason == null ? "Provider returned the payout" : failureReason
                );
            }

            return Outcome.APPLIED;
        });
    }

    /**
     * What the callback did, and the vocabulary the route answers in.
     *
     * <p>{@code ALREADY_TERMINAL} and {@code DUPLICATE} are deliberately distinct even though both
     * are 2xx no-ops: the first is a provider disagreeing with a decision PayMesh already made, the
     * second is the same message arriving twice. Collapsing them would hide the first inside the
     * second, and the first is the one worth investigating.
     */
    public enum Outcome {
        APPLIED,
        DUPLICATE,
        ALREADY_TERMINAL,
        UNKNOWN_PAYOUT
    }
}
