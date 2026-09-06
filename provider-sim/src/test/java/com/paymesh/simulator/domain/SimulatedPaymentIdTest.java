package com.paymesh.simulator.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-003, and one thing specific to this module: the prefix must not collide with any of PayMesh's.
 * A {@code sim_pay_} value travels into {@code payment_attempts.provider_reference}, so if it were
 * parseable as a {@code pay_} the two would be confusable in exactly the table where they sit
 * together.
 */
class SimulatedPaymentIdTest {

    @Test
    void generatesAnIdentifierWithTheSimulatedPaymentPrefix() {
        assertThat(SimulatedPaymentId.generate().value()).startsWith("sim_pay_");
    }

    @Test
    void generatesADistinctIdentifierEveryTime() {
        assertThat(SimulatedPaymentId.generate()).isNotEqualTo(SimulatedPaymentId.generate());
    }

    @Test
    void parsesAnIdentifierItGenerated() {
        SimulatedPaymentId generated = SimulatedPaymentId.generate();

        assertThat(SimulatedPaymentId.from(generated.value())).isEqualTo(generated);
    }

    @Test
    void rejectsAnIdentifierWithNoPrefix() {
        assertThatThrownBy(() -> SimulatedPaymentId.from(UUID.randomUUID().toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sim_pay_");
    }

    /**
     * The collision check, stated as a test rather than as a comment. {@code pay_} is PayMesh's
     * payment prefix; a simulator id must never be one.
     */
    @Test
    void rejectsAPayMeshPaymentIdentifier() {
        assertThatThrownBy(() -> SimulatedPaymentId.from("pay_" + UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sim_pay_");
    }

    @Test
    void rejectsAnIdentifierWhoseSuffixIsNotAUuid() {
        assertThatThrownBy(() -> SimulatedPaymentId.from("sim_pay_not-a-uuid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid UUID");
    }

    @Test
    void rejectsABlankIdentifier() {
        assertThatThrownBy(() -> SimulatedPaymentId.from("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullIdentifier() {
        assertThatThrownBy(() -> SimulatedPaymentId.from(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
