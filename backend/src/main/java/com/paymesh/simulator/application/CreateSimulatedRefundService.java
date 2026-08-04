package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedRefund;
import com.paymesh.simulator.domain.SimulatedRefundId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Sends money back, at the provider.
 *
 * <h2>Built now, called later</h2>
 *
 * PayMesh's Refund capability does not exist yet. This endpoint exists so that when it arrives it is
 * not blocked on this module, and so the reconciliation export can already report refunds -- a
 * provider truth file that could not say what went back out would be an incomplete one.
 *
 * <h2>No callback is queued, deliberately</h2>
 *
 * {@code /internal/v1/provider-callbacks} speaks only the four payment outcomes. A refund callback
 * today would be a row that can only retry into a 404 and end ABANDONED, and building a delivery
 * path for a receiver that does not exist is scaffolding. The dispatcher gains a refund row type in
 * the PR that builds the receiver.
 *
 * <h2>Three guards, one of which cannot be bypassed</h2>
 *
 * The row lock orders concurrent refunds, the aggregate produces a readable message, and
 * {@code ck_provider_payments_refunded} is what actually guarantees the sum never exceeds what was
 * captured. Only the last one survives a race, which is why it is in the database.
 */
public final class CreateSimulatedRefundService {

    private final SimulatedPaymentRepository payments;
    private final SimulatedRefundRepository refunds;
    private final FailureProfileRepository profiles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreateSimulatedRefundService(
        SimulatedPaymentRepository payments,
        SimulatedRefundRepository refunds,
        FailureProfileRepository profiles,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.payments = payments;
        this.refunds = refunds;
        this.profiles = profiles;
        this.transactions = transactions;
        this.clock = clock;
    }

    public SimulatedRefund refund(CreateSimulatedRefundCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Create Simulated Refund Command cannot be null");
        }

        String requestHash = RequestHashes.of(
            command.providerPaymentId().value(), command.amountMinor()
        );

        SimulatedRefund replay = replayFor(command.idempotencyKey(), requestHash);

        if (replay != null) {
            return replay;
        }

        try {
            return transactions.execute(status -> sendItBack(command, requestHash));
        } catch (IdempotencyKeyRaceLostException lost) {
            SimulatedRefund winner = replayFor(lost.idempotencyKey(), requestHash);

            if (winner == null) {
                throw new IllegalStateException(
                    "Idempotency key " + lost.idempotencyKey()
                        + " collided but the winning refund could not be read back",
                    lost
                );
            }

            return winner;
        }
    }

    private SimulatedRefund replayFor(String idempotencyKey, String requestHash) {
        return refunds.findByIdempotencyKey(idempotencyKey)
            .map(existing -> {
                if (!existing.requestHash().equals(requestHash)) {
                    throw new IdempotencyKeyReusedException(idempotencyKey);
                }

                return existing;
            })
            .orElse(null);
    }

    private SimulatedRefund sendItBack(CreateSimulatedRefundCommand command, String requestHash) {
        Instant now = Instant.now(clock);

        SimulatedPayment payment = payments.findByIdForUpdate(command.providerPaymentId())
            .orElseThrow(() -> new SimulatedPaymentNotFoundException(command.providerPaymentId()));

        SimulatedRefund refund = SimulatedRefund.start(
            SimulatedRefundId.generate(),
            payment.providerPaymentId(),
            command.callbackReference(),
            command.idempotencyKey(),
            requestHash,
            command.amountMinor(),
            profiles.get().defaultBehaviour(),
            now
        );

        // recordRefund runs FIRST even on the declined path, because its over-refund check is what
        // turns "more than was captured" into a 422 -- and that answer must not depend on whether
        // the simulated issuer happened to be in a declining mood. Asking for more than exists is a
        // malformed request either way.
        SimulatedPayment updated = payment.recordRefund(command.amountMinor(), now);

        // A declined refund moved nothing, so it must not consume the refundable balance. The check
        // above still ran; only its result is discarded.
        if (refund.movedMoney()) {
            payments.save(updated);
        }

        return refunds.save(refund);
    }
}
