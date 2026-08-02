package com.paymesh.refund.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-003's {@code ref_} prefix, reserved for this capability from the start. */
class RefundIdTest {

    @Test
    void mintsIdentifiersWithTheRefPrefix() {
        assertThat(RefundId.generate().value()).startsWith("ref_");
    }

    @Test
    void roundTripsAGeneratedIdentifier() {
        RefundId generated = RefundId.generate();

        assertThat(RefundId.from(generated.value())).isEqualTo(generated);
    }

    /** A payment intent id where a refund id is expected is a caller confusing two resources. */
    @Test
    void refusesAnotherCapabilitysIdentifier() {
        assertThatThrownBy(() -> RefundId.from("pi_00000000-0000-0000-0000-000000000001"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must start with ref_");
    }

    @Test
    void refusesAnIdentifierWithoutAValidUuid() {
        assertThatThrownBy(() -> RefundId.from("ref_not-a-uuid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid UUID");
    }

    @Test
    void refusesNullAndBlank() {
        assertThatThrownBy(() -> RefundId.from(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundId.from("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesBothProviderOutcomesCaseInsensitively() {
        assertThat(RefundOutcome.parse("succeeded")).isEqualTo(RefundOutcome.SUCCEEDED);
        assertThat(RefundOutcome.parse(" FAILED ")).isEqualTo(RefundOutcome.FAILED);
    }

    /** Payment's AUTHORIZED and REQUIRES_ACTION are not refund answers; a refund is asked once. */
    @Test
    void refusesAPaymentOutcomeThatIsNotARefundAnswer() {
        assertThatThrownBy(() -> RefundOutcome.parse("AUTHORIZED"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SUCCEEDED or FAILED");
    }
}
