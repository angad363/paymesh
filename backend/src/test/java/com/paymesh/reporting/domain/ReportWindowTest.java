package com.paymesh.reporting.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain JUnit: the window is framework-free and its rules are arithmetic. */
class ReportWindowTest {

    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void acceptsAWindowThatEndsAfterItStarts() {
        ReportWindow window = new ReportWindow(FROM, FROM.plus(Duration.ofDays(30)));

        assertThat(window.from()).isEqualTo(FROM);
        assertThat(window.to()).isEqualTo(FROM.plus(Duration.ofDays(30)));
    }

    /**
     * THE ONE THAT MATTERS. A zero-width window is not merely useless: because the interval is
     * half-open, {@code [t, t)} contains nothing, and accepting it would answer every question with
     * a confident empty report rather than telling the caller they asked for nothing.
     */
    @Test
    void rejectsAWindowThatEndsWhereItStarts() {
        assertThatThrownBy(() -> new ReportWindow(FROM, FROM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must end after it starts");
    }

    @Test
    void rejectsAWindowThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> new ReportWindow(FROM, FROM.minusSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must end after it starts");
    }

    @Test
    void rejectsAWindowWithNoBounds() {
        assertThatThrownBy(() -> new ReportWindow(null, FROM))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ReportWindow(FROM, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The cap is what stops a caller passing {@code from=1970} and asking for an unbounded scan. */
    @Test
    void rejectsAWindowLongerThanTheCap() {
        Instant justOver = FROM.plus(Duration.ofDays(ReportWindow.MAX_DAYS)).plusSeconds(1);

        assertThatThrownBy(() -> new ReportWindow(FROM, justOver))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot exceed");
    }

    /** The boundary itself is legal -- a cap that rejected exactly the cap would be off by one. */
    @Test
    void acceptsAWindowOfExactlyTheCap() {
        Instant exactly = FROM.plus(Duration.ofDays(ReportWindow.MAX_DAYS));

        assertThat(new ReportWindow(FROM, exactly).to()).isEqualTo(exactly);
    }
}
