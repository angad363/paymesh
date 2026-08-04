package com.paymesh.reconciliation.infrastructure.payment;

import com.paymesh.reconciliation.application.PaymentRepair;
import com.paymesh.reconciliation.application.RepairOutcome;
import com.paymesh.reconciliation.application.ReplayedOutcome;
import com.paymesh.payment.application.PaymentIntentNotFoundException;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.ProviderCallbackOutcome;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The one file allowed to name both this module and Payment (ADR-008, ADR-026).
 *
 * <h2>It adapts, it does not decide</h2>
 *
 * Everything here is translation: this module's {@link ReplayedOutcome} into Payment's
 * {@code ProviderOutcome}, Payment's {@code ProviderCallbackOutcome} into this module's
 * {@link RepairOutcome}. No transition rule, no amount check, no staleness judgement -- all of that
 * stays inside {@code RecordProviderCallbackService}, which is the whole point of replaying rather
 * than repairing.
 * <p>
 * It lives in {@code infrastructure} for the reason every other cross-module adapter here does: an
 * adapter cannot avoid naming the thing it adapts, so the naming is confined to one file that
 * nothing in {@code application} can see. {@code ModuleBoundaryTest} allowlists exactly this path.
 */
public final class PaymentModuleRepair implements PaymentRepair {

    private final RecordProviderCallbackService callbacks;
    private final String provider;

    public PaymentModuleRepair(RecordProviderCallbackService callbacks, String provider) {
        this.callbacks = callbacks;
        this.provider = provider;
    }

    @Override
    public RepairOutcome replay(
        String paymentIntentId,
        String providerReference,
        ReplayedOutcome outcome,
        Long authorizedAmountMinor,
        Long capturedAmountMinor,
        String failureCode,
        String failureMessage,
        String eventId,
        Instant occurredAt
    ) {
        ProviderEvent event = new ProviderEvent(
            eventId,
            occurredAt,
            paymentIntentId,
            providerReference,
            ProviderOutcome.valueOf(outcome.name()),
            authorizedAmountMinor,
            capturedAmountMinor,
            failureCode,
            failureMessage,
            // No action URL. A reconciliation file records what HAPPENED; an off-site challenge URL
            // is an instruction to a customer who is no longer at their browser, and a stale one
            // would be worse than none.
            null
        );

        try {
            return toRepairOutcome(
                callbacks.record(new RecordProviderCallbackCommand(provider, event, payloadHash(eventId)))
            );
        } catch (PaymentIntentNotFoundException unknown) {
            // The provider has a payment PayMesh has no record of. NEVER created here: a payment
            // intent conjured from a provider's file would be money movement invented out of a
            // document, which is the one thing reconciliation must not do. Reported and left alone.
            return RepairOutcome.UNRESOLVED;
        }
    }

    /**
     * APPLIED is the only outcome that changed anything, so it is the only one that counts as a
     * repair.
     * <p>
     * The other three are all "PayMesh was already right, or already knew better", and lumping them
     * together is deliberate rather than lazy: DUPLICATE means this exact reconciliation already ran,
     * IGNORED_STALE means a newer callback has since superseded the provider's file, and
     * IGNORED_TERMINAL means the payment had already settled. In every one of them the correct
     * action was to do nothing, and each is recorded on the callback row for whoever wants the
     * detail.
     */
    private static RepairOutcome toRepairOutcome(ProviderCallbackOutcome outcome) {
        return outcome == ProviderCallbackOutcome.APPLIED
            ? RepairOutcome.REPAIRED
            : RepairOutcome.ALREADY_CONSISTENT;
    }

    /**
     * There were no raw bytes to hash -- nothing arrived over the wire -- so this hashes the claim
     * this job reconstructed instead.
     * <p>
     * Honest rather than convenient: the stored hash then says "this is the reconciliation event
     * identified by this id", which is true, and it stays deterministic so a re-run produces the same
     * value. Passing a constant or the empty string would have compiled and would have made every
     * reconciled row indistinguishable in the audit table.
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
