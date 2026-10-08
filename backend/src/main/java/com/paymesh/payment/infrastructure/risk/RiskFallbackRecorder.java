package com.paymesh.payment.infrastructure.risk;

/**
 * The testable evidence surface for "the risk service was unreachable, and here is what PayMesh
 * did about it" (ADR-044 section 3). One method, and the one-implementation-interface rule this
 * codebase otherwise avoids is justified here on purpose: the acceptance criterion for this PR is
 * that an unreachable risk service "records that it did" something, and a fake implementation is
 * what lets a test assert on that without parsing log output.
 */
public interface RiskFallbackRecorder {

    /**
     * @param decision {@code "ALLOW"} or {@code "BLOCK"} -- what {@link RiskUnavailablePolicy}
     *                 decided in Risk's absence, not what Risk would have decided.
     * @param reason   why Risk could not be reached (timeout, connection refusal, circuit open),
     *                 for whoever has to explain this later.
     */
    void record(String paymentIntentId, long amountMinor, String decision, String reason);
}
