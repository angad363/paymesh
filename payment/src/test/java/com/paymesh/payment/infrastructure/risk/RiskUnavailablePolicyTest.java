package com.paymesh.payment.infrastructure.risk;

import com.paymesh.payment.application.RiskCheck.Decision;
import com.paymesh.payment.application.RiskUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The by-tier fallback policy ADR-044 defines for "risk service unreachable" -- distinct from
 * ADR-030's uniform fail-closed Redis-outage policy, which this class deliberately does not repeat.
 * Plain JUnit: no I/O, no Spring context, a fake recorder as the evidence surface.
 */
class RiskUnavailablePolicyTest {

    private static final long THRESHOLD = 50_000L;

    private final RecordingFallbackRecorder recorder = new RecordingFallbackRecorder();
    private final RiskUnavailablePolicy policy = new RiskUnavailablePolicy(THRESHOLD, recorder);

    @Test
    void belowThresholdAllowsAndRecordsTheAllow() {
        Decision decision = policy.apply("pi_1", THRESHOLD - 1, "connection refused");

        assertThat(decision.permitted()).isTrue();
        assertThat(decision.assessmentId())
            .as("Risk never ran, so there is no assessment to point at")
            .isNull();

        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0).decision()).isEqualTo("ALLOW");
        assertThat(recorder.calls.get(0).paymentIntentId()).isEqualTo("pi_1");
        assertThat(recorder.calls.get(0).amountMinor()).isEqualTo(THRESHOLD - 1);
        assertThat(recorder.calls.get(0).reason()).isEqualTo("connection refused");
    }

    @Test
    void atOrAboveThresholdRefusesAndRecordsTheBlock() {
        assertThatThrownBy(() -> policy.apply("pi_2", THRESHOLD, "timeout"))
            .isInstanceOf(RiskUnavailableException.class)
            .extracting(exception -> ((RiskUnavailableException) exception).paymentIntentId())
            .isEqualTo("pi_2");

        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0).decision()).isEqualTo("BLOCK");
        assertThat(recorder.calls.get(0).amountMinor()).isEqualTo(THRESHOLD);
    }

    @Test
    void aboveThresholdAlsoRefuses() {
        assertThatThrownBy(() -> policy.apply("pi_3", THRESHOLD + 1, "circuit breaker open"))
            .isInstanceOf(RiskUnavailableException.class);
    }

    @Test
    void recordsBeforeThrowing() {
        // The recorder having exactly one BLOCK call after the throw IS the proof: if the record
        // call happened after the throw it would never run at all.
        assertThatThrownBy(() -> policy.apply("pi_4", THRESHOLD, "any"))
            .isInstanceOf(RiskUnavailableException.class);

        assertThat(recorder.calls).hasSize(1);
    }

    @Test
    void rejectsANonPositiveThreshold() {
        assertThatThrownBy(() -> new RiskUnavailablePolicy(0, recorder))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingFallbackRecorder implements RiskFallbackRecorder {

        private final List<Call> calls = new ArrayList<>();

        @Override
        public void record(String paymentIntentId, long amountMinor, String decision, String reason) {
            calls.add(new Call(paymentIntentId, amountMinor, decision, reason));
        }

        private record Call(String paymentIntentId, long amountMinor, String decision, String reason) {
        }
    }
}
