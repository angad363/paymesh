package com.paymesh.simulator.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedRefundIdTest {

    @Test
    void generatesAnIdentifierWithTheSimulatedRefundPrefix() {
        assertThat(SimulatedRefundId.generate().value()).startsWith("sim_ref_");
    }

    @Test
    void parsesAnIdentifierItGenerated() {
        SimulatedRefundId generated = SimulatedRefundId.generate();

        assertThat(SimulatedRefundId.from(generated.value())).isEqualTo(generated);
    }

    /**
     * {@code ref_} is reserved for PayMesh's own Refund capability, which lands later. The two ids
     * will one day sit in adjacent columns, so neither may parse as the other.
     */
    @Test
    void rejectsAPayMeshRefundIdentifier() {
        assertThatThrownBy(() -> SimulatedRefundId.from("ref_" + UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sim_ref_");
    }

    @Test
    void rejectsASimulatedPaymentIdentifier() {
        assertThatThrownBy(() -> SimulatedRefundId.from("sim_pay_" + UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sim_ref_");
    }

    @Test
    void rejectsAnIdentifierWhoseSuffixIsNotAUuid() {
        assertThatThrownBy(() -> SimulatedRefundId.from("sim_ref_not-a-uuid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid UUID");
    }

    @Test
    void rejectsANullIdentifier() {
        assertThatThrownBy(() -> SimulatedRefundId.from(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
