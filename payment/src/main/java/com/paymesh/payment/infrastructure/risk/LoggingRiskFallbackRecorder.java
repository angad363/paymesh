package com.paymesh.payment.infrastructure.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link RiskFallbackRecorder}: a structured WARN log line. Not a database row --
 * unlike {@code risk_assessments}, a fallback decision is operational noise to page on, not
 * evidence a support agent looks up by id (Risk itself never ran, so there is no assessment to
 * point at). If this ever needs to be queried rather than alerted on, promote it to a table then;
 * a log line is the smallest thing that satisfies "records that it did" today.
 */
public final class LoggingRiskFallbackRecorder implements RiskFallbackRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingRiskFallbackRecorder.class);

    @Override
    public void record(String paymentIntentId, long amountMinor, String decision, String reason) {
        log.warn(
            "Risk service unreachable, fell back to {} paymentIntentId={} amountMinor={} reason={}",
            decision, paymentIntentId, amountMinor, reason
        );
    }
}
