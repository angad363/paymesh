package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.CallbackBody;
import com.paymesh.simulator.domain.FailureProfile;
import com.paymesh.simulator.domain.OutboundCallback;
import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedPaymentId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Captures an authorization and queues the SUCCEEDED callback that reports it.
 * <p>
 * This is the second half of the MANUAL-capture flow and the reason
 * {@link com.paymesh.simulator.domain.SimulatedCaptureMethod} exists: ADR-012 section 4 refuses
 * AUTHORIZED to SUCCEEDED as a <em>provider</em> transition, because a capture is something the
 * merchant asks for. So the provider authorizes, PayMesh's merchant captures, and only then does the
 * provider report a collection -- against an intent PayMesh has already moved.
 *
 * <h2>No idempotency key, deliberately</h2>
 *
 * Unlike create, capture carries no provider-side key. The state machine already makes a repeat
 * safe: capture is legal only from AUTHORIZED, so a second call is a 409 rather than a second
 * collection. A key here would add a table lookup and a reuse rule to protect an invariant the
 * aggregate already protects, which is two mechanisms where one is doing the work.
 */
public final class CaptureSimulatedPaymentService {

    private final SimulatedPaymentRepository payments;
    private final OutboundCallbackRepository callbacks;
    private final FailureProfileRepository profiles;
    private final CallbackBodyWriter bodyWriter;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CaptureSimulatedPaymentService(
        SimulatedPaymentRepository payments,
        OutboundCallbackRepository callbacks,
        FailureProfileRepository profiles,
        CallbackBodyWriter bodyWriter,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.payments = payments;
        this.callbacks = callbacks;
        this.profiles = profiles;
        this.bodyWriter = bodyWriter;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param requestedAmountMinor null captures the full authorized amount, which is what a caller
     *                             that simply wants its money means
     * @throws SimulatedPaymentNotFoundException                                       unknown id
     * @throws com.paymesh.simulator.domain.SimulatedPaymentNotCapturableException     not AUTHORIZED
     */
    public SimulatedPayment capture(
        SimulatedPaymentId providerPaymentId,
        Long requestedAmountMinor
    ) {
        if (providerPaymentId == null) {
            throw new IllegalArgumentException("A simulated payment identifier is required");
        }

        return transactions.execute(status -> {
            Instant now = Instant.now(clock);

            // FOR UPDATE, so two concurrent captures cannot both read AUTHORIZED and both pass the
            // aggregate's check. The loser waits, re-reads CAPTURED, and is refused.
            SimulatedPayment payment = payments.findByIdForUpdate(providerPaymentId)
                .orElseThrow(() -> new SimulatedPaymentNotFoundException(providerPaymentId));

            SimulatedPayment captured = payments.save(payment.capture(
                requestedAmountMinor == null ? payment.amountMinor() : requestedAmountMinor,
                now
            ));

            FailureProfile profile = profiles.get();

            // The captured amount, not the authorized one. PayMesh checks a SUCCEEDED callback's
            // capturedAmountMinor against the intent's own figure, so a partial capture reported as
            // the full amount would be refused as a claim the intent does not authorize (SDD 12.3).
            CallbackBody body = CallbackBody.succeeded(
                OutboundCallback.newEventId(),
                captured.callbackReference(),
                captured.providerPaymentId(),
                captured.capturedAmountMinor(),
                now
            );

            callbacks.save(OutboundCallback.enqueue(
                body.eventId(),
                captured.providerPaymentId(),
                captured.callbackReference(),
                body.outcome(),
                body.occurredAt(),
                now.plus(profile.callbackDelay()),
                bodyWriter.write(body),
                now
            ));

            return captured;
        });
    }
}
