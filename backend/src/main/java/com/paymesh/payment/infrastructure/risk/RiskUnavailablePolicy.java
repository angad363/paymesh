package com.paymesh.payment.infrastructure.risk;

import com.paymesh.payment.application.RiskCheck.Decision;
import com.paymesh.payment.application.RiskUnavailableException;

/**
 * WHAT PAYMESH DOES WHEN RISK CANNOT BE REACHED AT ALL (ADR-044 section 3) -- the "risk service
 * unreachable" case ADR-030 explicitly deferred as "the line to revisit" when it built the
 * Redis-outage fail-open/fail-closed policy for a datastore that, in the end, this platform never
 * added (SDD 14.6: Postgres already had the rows).
 *
 * <h2>BY TIER, NOT UNIFORM</h2>
 *
 * ADR-030's Redis-outage policy failed closed uniformly: any amount, any outage, refuse. This
 * policy is deliberately different, and the difference is the whole point of this class. A risk
 * OUTAGE is not a risk REFUSAL -- Risk did not look at this payment and say no; nobody looked at
 * it at all. Refusing every payment on the platform because one dependency is briefly unreachable
 * trades a contained problem (the network) for an uncontained one (every confirm fails). So the
 * amount decides which failure mode is worse:
 *
 * <ul>
 *   <li><b>At or above {@code blockAtOrAboveMinor}:</b> refuse. A large payment going through
 *       unevaluated is the more expensive mistake, so this tier fails CLOSED -- the same shape
 *       ADR-030 chose uniformly, now scoped to the payments where it is worth the cost.</li>
 *   <li><b>Below it:</b> allow, with no assessment (Risk never ran, so there is nothing to point
 *       at) -- this tier fails OPEN. A confirm that hangs or refuses on every network blip for a
 *       payment this small is the more expensive mistake.</li>
 * </ul>
 *
 * <p>Either way, {@link RiskFallbackRecorder#record} runs BEFORE the method returns or throws --
 * "does not silently allow" means the allow is on the record, not merely that it happened.
 */
public final class RiskUnavailablePolicy {

    private final long blockAtOrAboveMinor;
    private final RiskFallbackRecorder recorder;

    public RiskUnavailablePolicy(long blockAtOrAboveMinor, RiskFallbackRecorder recorder) {
        if (blockAtOrAboveMinor <= 0) {
            throw new IllegalArgumentException(
                "Risk unavailable policy block threshold must be positive"
            );
        }

        this.blockAtOrAboveMinor = blockAtOrAboveMinor;
        this.recorder = recorder;
    }

    /**
     * @throws RiskUnavailableException when {@code amountMinor} meets or exceeds the configured
     *     threshold. {@link com.paymesh.payment.api.PaymentExceptionHandler} maps it to 503 --
     *     retryable, and distinct from a real block.
     */
    public Decision apply(String paymentIntentId, long amountMinor, String reason) {
        if (amountMinor >= blockAtOrAboveMinor) {
            recorder.record(paymentIntentId, amountMinor, "BLOCK", reason);

            throw new RiskUnavailableException(paymentIntentId, reason);
        }

        recorder.record(paymentIntentId, amountMinor, "ALLOW", reason);

        // No assessment id: Risk never ran, and inventing one would claim evidence that does not
        // exist. See RiskCheck's own javadoc on why the id is otherwise always present.
        return new Decision(true, null);
    }
}
