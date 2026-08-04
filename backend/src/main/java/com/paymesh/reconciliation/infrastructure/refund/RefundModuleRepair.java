package com.paymesh.reconciliation.infrastructure.refund;

import com.paymesh.reconciliation.application.RefundRepair;
import com.paymesh.reconciliation.application.RepairOutcome;
import com.paymesh.refund.application.RecordRefundCallbackCommand;
import com.paymesh.refund.application.RecordRefundCallbackService;
import com.paymesh.refund.domain.RefundCallbackOutcome;
import com.paymesh.refund.domain.RefundEvent;
import com.paymesh.refund.domain.RefundOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The one file allowed to name both this module and Refund. The mirror of
 * {@code PaymentModuleRepair}, and the close of ADR-019's named gap (ADR-026).
 *
 * <h2>What this actually fixes</h2>
 *
 * A refund whose callback never arrived used to sit in PROCESSING forever, holding its amount
 * against the payment's captured total so the merchant could not refund the rest. ADR-023 added a
 * sweeper that eventually fails it, and was explicit that failing it is <b>a guess in the safe
 * direction</b>: if the provider did send the money back, PayMesh now believes it did not, and the
 * merchant's refundable balance is overstated by that amount.
 * <p>
 * This replaces the guess with the provider's own record. A refund the provider completed reaches
 * SUCCEEDED even after the sweeper failed it -- and if the refund aggregate refuses that transition
 * as terminal, the callback row still lands, which is the audit trail whoever reconciles the money by
 * hand will need.
 */
public final class RefundModuleRepair implements RefundRepair {

    private final RecordRefundCallbackService callbacks;
    private final String provider;

    public RefundModuleRepair(RecordRefundCallbackService callbacks, String provider) {
        this.callbacks = callbacks;
        this.provider = provider;
    }

    @Override
    public RepairOutcome replay(
        String refundId,
        String providerReference,
        boolean succeeded,
        String failureCode,
        String failureMessage,
        String eventId,
        Instant occurredAt
    ) {
        RefundEvent event = new RefundEvent(
            eventId,
            occurredAt,
            refundId,
            providerReference,
            succeeded ? RefundOutcome.SUCCEEDED : RefundOutcome.FAILED,
            failureCode,
            failureMessage
        );

        return toRepairOutcome(
            callbacks.record(new RecordRefundCallbackCommand(provider, event, payloadHash(eventId)))
        );
    }

    /**
     * APPLIED is the only outcome that moved anything, so it is the only repair.
     *
     * <h2>NOT_APPLICABLE IS AMBIGUOUS ON REFUND'S SIDE, AND THIS MAPPING LOSES THAT</h2>
     *
     * Stating it rather than letting a reader assume the count is exact. {@code RecordRefundCallback
     * Service} returns NOT_APPLICABLE for TWO different facts: a new event for a refund that has
     * already settled (its declared meaning), and a callback naming a refund <b>that does not
     * exist</b> -- which it cannot record, because {@code refund_callbacks} has a foreign key to
     * {@code refunds}, and which it therefore logs and returns the same value for.
     * <p>
     * The first is genuinely ALREADY_CONSISTENT. The second is the refund equivalent of
     * {@code PaymentIntentNotFoundException} and ought to be UNRESOLVED. They are indistinguishable
     * from here, so <b>both are counted as ALREADY_CONSISTENT</b> and the choice is deliberate: a
     * settled refund is by far the common case on any healthy platform, so mapping the pair to
     * UNRESOLVED would make that count permanently large and meaningless -- the same
     * always-red-is-the-same-as-off failure the outbox health indicator avoids.
     * <p>
     * What survives intact is the number that matters: REPAIRED is exact either way. The unmatchable
     * refund is still caught upstream when it has no reference at all, and the residual case -- a
     * reference naming a refund PayMesh never created -- is logged by Refund itself. Recorded as an
     * open item; closing it properly means a distinct outcome value on Refund's enum, which is
     * Refund's PR and not this one.
     */
    private static RepairOutcome toRepairOutcome(RefundCallbackOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> RepairOutcome.REPAIRED;
            case DUPLICATE, STALE, NOT_APPLICABLE -> RepairOutcome.ALREADY_CONSISTENT;
        };
    }

    /**
     * {@code RecordRefundCallbackCommand} REQUIRES 64 hex characters and refuses anything else, so
     * this is a constraint rather than a convention. Same reasoning as the payment adapter: no bytes
     * arrived, so the reconstructed claim's identity is hashed instead, deterministically.
     */
    private static String payloadHash(String eventId) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(eventId.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
