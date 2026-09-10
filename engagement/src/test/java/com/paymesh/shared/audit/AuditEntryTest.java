package com.paymesh.shared.audit;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The builder is the caller-facing API of the audit port; it carries its own copy of the actor invariant. */
class AuditEntryTest {

    @Test
    void buildsAUserEntry() {
        AuditEntry entry = AuditEntry.builder("merchant.suspended", ActorType.USER)
            .actorId("usr_x")
            .merchant(MerchantId.generate())
            .resource("merchant", "mrc_x")
            .reason("fraud")
            .changing("ACTIVE", "SUSPENDED")
            .from("203.0.113.7")
            .build();

        assertThat(entry.action()).isEqualTo("merchant.suspended");
        assertThat(entry.before()).isEqualTo("ACTIVE");
        assertThat(entry.ip()).isEqualTo("203.0.113.7");
    }

    @Test
    void refusesAUserEntryWithNoActor() {
        assertThatThrownBy(() -> AuditEntry.builder("merchant.suspended", ActorType.USER)
            .resource("merchant", "mrc_x")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs an actor id");
    }

    @Test
    void refusesASystemEntryThatCarriesAnActor() {
        assertThatThrownBy(() -> AuditEntry.builder("payment.recovered", ActorType.SYSTEM)
            .actorId("usr_x")
            .resource("payment_intent", "pi_x")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("carries no actor id");
    }

    @Test
    void refusesAMissingActionOrResource() {
        assertThatThrownBy(() -> AuditEntry.builder(" ", ActorType.SYSTEM).resource("x", "y").build())
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AuditEntry.builder("a.b", ActorType.SYSTEM).build())
            .isInstanceOf(IllegalArgumentException.class);
    }
}
