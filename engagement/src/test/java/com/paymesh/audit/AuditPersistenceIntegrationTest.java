package com.paymesh.audit;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.audit.application.AuditEventQuery;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.audit.domain.AuditWindow;
import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WHAT MAKES THE AUDIT LOG TRUSTWORTHY IS NOT THE JAVA. Append-only is a {@code BEFORE UPDATE OR
 * DELETE} trigger and the actor rule is a CHECK, both in V36, and both are proven here by issuing
 * raw SQL with the application entirely out of the path -- the same way {@code LedgerIntegrationTest}
 * proves the ledger's. A trigger dropped or a CHECK weakened turns exactly these tests red while the
 * Java-level tests stay green.
 *
 * <p>Not {@code @Transactional}: the immutability tests must commit a row through the ordinary path
 * and then attack it in a separate statement.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AuditPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private final AuditEventRepository events;
    private final JdbcClient jdbc;

    @Autowired
    AuditPersistenceIntegrationTest(AuditEventRepository events, JdbcClient jdbc) {
        this.events = events;
        this.jdbc = jdbc;
    }

    @Test
    void appendsAndReadsBackAnEvent() {
        AuditEvent appended = events.append(userEvent(
            "merchant.suspended", "usr_op", MerchantId.generate(), "mrc_res", NOW
        ));

        AuditEvent read = events.findById(appended.id()).orElseThrow();

        assertThat(read.action()).isEqualTo("merchant.suspended");
        assertThat(read.actorType()).isEqualTo(ActorType.USER);
        assertThat(read.actorId()).isEqualTo("usr_op");
    }

    /** The correction path for a wrong audit row is a NEW row; editing one is refused by the trigger. */
    @Test
    void refusesToUpdateAnAuditEvent() {
        AuditEvent appended = events.append(userEvent(
            "merchant.suspended", "usr_op", MerchantId.generate(), "mrc_res", NOW
        ));

        assertThatThrownBy(() -> jdbc
            .sql("update audit_events set action = 'tampered' where audit_event_id = ?")
            .param(appended.id().value())
            .update())
            .hasStackTraceContaining("immutable");
    }

    @Test
    void refusesToDeleteAnAuditEvent() {
        AuditEvent appended = events.append(userEvent(
            "merchant.suspended", "usr_op", MerchantId.generate(), "mrc_res", NOW
        ));

        assertThatThrownBy(() -> jdbc
            .sql("delete from audit_events where audit_event_id = ?")
            .param(appended.id().value())
            .update())
            .hasStackTraceContaining("immutable");
    }

    /** ck_audit_events_actor_id: a SYSTEM row with an operator is a contradiction the DB refuses. */
    @Test
    void refusesASystemRowThatCarriesAnActor() {
        assertThatThrownBy(() -> rawInsert("SYSTEM", "usr_x", "payment.recovered"))
            .hasStackTraceContaining("ck_audit_events_actor_id");
    }

    /** And a USER row with no operator -- the audit entry that cannot answer "who". */
    @Test
    void refusesAUserRowWithNoActor() {
        assertThatThrownBy(() -> rawInsert("USER", null, "merchant.suspended"))
            .hasStackTraceContaining("ck_audit_events_actor_id");
    }

    @Test
    void searchFiltersAndReturnsNewestFirst() {
        // This class is not @Transactional (the immutability tests commit rows), so sibling tests
        // leave "merchant.suspended" rows behind. Fresh merchant ids and a unique actor per run keep
        // every assertion here scoped to this test's own rows.
        MerchantId a = MerchantId.generate();
        MerchantId b = MerchantId.generate();
        String actor = "usr_" + UUID.randomUUID();
        String otherActor = "usr_" + UUID.randomUUID();

        events.append(userEvent("merchant.suspended", actor, a, "mrc_a", NOW));
        events.append(userEvent("merchant.activated", otherActor, a, "mrc_a", NOW.plusSeconds(60)));
        events.append(userEvent("merchant.suspended", actor, b, "mrc_b", NOW.plusSeconds(120)));

        // Filter by merchant a: two events, newest (activated) first.
        List<AuditEvent> byMerchant = events.search(
            new AuditEventQuery(a, null, null, null, 50)
        );
        assertThat(byMerchant).hasSize(2);
        assertThat(byMerchant.get(0).action()).isEqualTo("merchant.activated");

        // Filter by action, scoped to this run's actor so sibling rows do not leak in.
        List<AuditEvent> byAction = events.search(
            new AuditEventQuery(null, "merchant.suspended", actor, null, 50)
        );
        assertThat(byAction).extracting(AuditEvent::action)
            .containsOnly("merchant.suspended")
            .hasSize(2);

        // Filter by actor alone.
        assertThat(events.search(new AuditEventQuery(null, null, otherActor, null, 50)))
            .hasSize(1);
    }

    @Test
    void findInWindowIsOldestFirstAndRespectsTheMerchantFilter() {
        MerchantId a = MerchantId.generate();
        MerchantId b = MerchantId.generate();

        events.append(userEvent("merchant.suspended", "usr_1", a, "mrc_a", NOW));
        events.append(userEvent("merchant.activated", "usr_1", a, "mrc_a", NOW.plusSeconds(60)));
        events.append(userEvent("merchant.suspended", "usr_2", b, "mrc_b", NOW.plusSeconds(30)));

        AuditWindow window = new AuditWindow(NOW.minusSeconds(1), NOW.plusSeconds(3600));

        // One tenant, oldest first.
        List<AuditEvent> forA = events.findInWindow(window, a, 100);
        assertThat(forA).hasSize(2);
        assertThat(forA.get(0).action()).isEqualTo("merchant.suspended");
        assertThat(forA.get(1).action()).isEqualTo("merchant.activated");

        // No filter: every tenant.
        assertThat(events.findInWindow(window, null, 100)).hasSize(3);
    }

    // --- helpers --------------------------------------------------------------------------------

    private static AuditEvent userEvent(
        String action, String actorId, MerchantId merchantId, String resourceId, Instant at
    ) {
        return AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, actorId, merchantId,
            action, "merchant", resourceId, null, null, null, null, at
        );
    }

    private void rawInsert(String actorType, String actorId, String action) {
        jdbc.sql("""
                insert into audit_events
                    (audit_event_id, actor_type, actor_id, action, resource_type, occurred_at)
                values (?, ?, ?, ?, 'merchant', now())
                """)
            .param(AuditEventId.generate().value())
            .param(actorType)
            .param(actorId)
            .param(action)
            .update();
    }
}
