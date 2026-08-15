package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.OutboundCallback;
import com.paymesh.simulator.domain.PayoutCallbackBody;
import com.paymesh.simulator.domain.SimulatedOutcome;
import com.paymesh.simulator.domain.SimulatedPayout;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * A payout the provider was asked to make. SDD 13.3, and the gap ADR-017 recorded.
 *
 * <h2>THE ANSWER IS A QUEUED CALLBACK, NOT THE RESPONSE BODY</h2>
 *
 * The response says the provider accepted the instruction. Whether the money moved arrives later,
 * over the wire, signed -- which is the whole reason this module exists rather than being a stub
 * that returns a status. A caller that treated the 201 as confirmation would post its ledger's cash
 * entry on the provider's promise.
 *
 * <h2>Idempotent on the caller's reference</h2>
 *
 * A resubmission returns the ORIGINAL payout and queues NO SECOND CALLBACK. Both halves matter: a
 * second row would move money twice, and a second callback would arrive with a fresh event id and
 * so survive PayMesh's dedup, applying the same outcome again to a payout that had already been
 * answered.
 */
public final class CreateSimulatedPayoutService {

    private final SimulatedPayoutRepository payouts;
    private final OutboundCallbackRepository callbacks;
    private final CallbackBodyWriter bodyWriter;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreateSimulatedPayoutService(
        SimulatedPayoutRepository payouts,
        OutboundCallbackRepository callbacks,
        CallbackBodyWriter bodyWriter,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.payouts = payouts;
        this.callbacks = callbacks;
        this.bodyWriter = bodyWriter;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** @return the payout, and whether this call is the one that created it */
    public Result create(CreateSimulatedPayoutCommand command) {
        return transactions.execute(status -> {
            Instant now = Instant.now(clock);

            return payouts.findByExternalReference(command.externalReference())
                .map(existing -> new Result(existing, false))
                .orElseGet(() -> {
                    SimulatedPayout payout = payouts.save(SimulatedPayout.accept(
                        command.externalReference(),
                        command.destination(),
                        command.amountMinor(),
                        command.currency(),
                        now
                    ));

                    // The payout row and the callback that reports it commit together. A payout
                    // recorded with no callback queued is a merchant never told they were paid.
                    callbacks.save(queueAnswer(payout, now));

                    return new Result(payout, true);
                });
        });
    }

    private OutboundCallback queueAnswer(SimulatedPayout payout, Instant now) {
        String eventId = OutboundCallback.newEventId();

        return OutboundCallback.enqueuePayout(
            eventId,
            payout.providerPayoutId(),
            payout.externalReference(),
            payout.wasPaid() ? SimulatedOutcome.SUCCEEDED : SimulatedOutcome.FAILED,
            now,
            // Due immediately. The delay knobs on this queue exist to reproduce a provider being
            // slow; a payout that is slow by default would just make every test wait.
            now,
            bodyWriter.writePayout(PayoutCallbackBody.of(eventId, payout, now)),
            now
        );
    }

    /** @param created false when this was a resubmission and the original row was returned */
    public record Result(SimulatedPayout payout, boolean created) {
    }
}
