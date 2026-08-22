package com.paymesh.audit.domain;

import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The one invariant this aggregate enforces itself rather than leaving to the database: an audit
 * event that cannot answer "who". Everything else about a row -- append-only, the actor CHECK -- is
 * the database's, and {@code AuditPersistenceIntegrationTest} proves those with the app out of the
 * path. This proves the domain refuses the same bad shapes early, as a readable error.
 */
class AuditEventTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void recordsAUserActionWithAnActor() {
        AuditEvent event = AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, "usr_x", MerchantId.generate(),
            "merchant.suspended", "merchant", "mrc_x", "fraud", "ACTIVE-hash", "SUSPENDED-hash",
            "ip-hash", NOW
        );

        assertThat(event.actorType()).isEqualTo(ActorType.USER);
        assertThat(event.actorId()).isEqualTo("usr_x");
        assertThat(event.action()).isEqualTo("merchant.suspended");
    }

    @Test
    void recordsASystemActionWithNoActorAndNoMerchant() {
        AuditEvent event = AuditEvent.record(
            AuditEventId.generate(), ActorType.SYSTEM, null, null,
            "payment.recovered", "payment_intent", "pi_x", null, null, null, null, NOW
        );

        assertThat(event.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(event.actorId()).isNull();
        assertThat(event.merchantId()).isNull();
    }

    @Test
    void refusesAUserActionWithNoActor() {
        Throwable thrown = catchThrowable(() -> AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, null, MerchantId.generate(),
            "merchant.suspended", "merchant", "mrc_x", null, null, null, null, NOW
        ));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs an actor id");
    }

    @Test
    void refusesASystemActionThatCarriesAnActor() {
        assertThatThrownBy(() -> AuditEvent.record(
            AuditEventId.generate(), ActorType.SYSTEM, "usr_x", null,
            "payment.recovered", "payment_intent", "pi_x", null, null, null, null, NOW
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SYSTEM audit event carries no actor id");
    }

    @Test
    void refusesAReasonBeyondTheCap() {
        String tooLong = "x".repeat(AuditEvent.MAX_REASON_LENGTH + 1);

        assertThatThrownBy(() -> AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, "usr_x", MerchantId.generate(),
            "merchant.suspended", "merchant", "mrc_x", tooLong, null, null, null, NOW
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason cannot exceed");
    }

    @Test
    void refusesAMissingActionOrResourceOrTime() {
        assertThatThrownBy(() -> AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, "usr_x", null,
            " ", "merchant", "mrc_x", null, null, null, null, NOW
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, "usr_x", null,
            "merchant.suspended", " ", "mrc_x", null, null, null, null, NOW
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, "usr_x", null,
            "merchant.suspended", "merchant", "mrc_x", null, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
