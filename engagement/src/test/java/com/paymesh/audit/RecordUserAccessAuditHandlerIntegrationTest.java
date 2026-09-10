package com.paymesh.audit;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.audit.application.AuditEventQuery;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROVES THE MONOLITH SIDE OF ADR-043 SECTION 5, FOR IDENTITY'S TWO SHAPES: an
 * {@code identity.user_access.audited} envelope, exactly what {@code ManageUserAccessService}'s own
 * outbox now produces (Identity is still in the monolith), turns into the same {@code audit_events}
 * row the in-process {@code AuditRecorder.record} call used to write directly.
 *
 * <p>Two cases, because this event is the one that genuinely carries a null merchant: a
 * platform-scoped action (suspend, reactivate, close, platform-role grant/revoke) names no tenant at
 * all, where a merchant-scoped action (access_granted/access_revoked) carries a real one. Both are
 * asserted here because {@link OutboxEvent#merchantId()} being nullable (ADR-043) is exactly the
 * design point this test exists to prove did not get lost on the way to {@code audit_events}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class RecordUserAccessAuditHandlerIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-20T10:00:00Z");

    private final EventDispatcher dispatcher;
    private final AuditEventRepository events;

    @Autowired
    RecordUserAccessAuditHandlerIntegrationTest(EventDispatcher dispatcher, AuditEventRepository events) {
        this.dispatcher = dispatcher;
        this.events = events;
    }

    @Test
    void aPlatformScopedUserAccessAuditedEventWritesAnAuditEventRowWithNoMerchant() {
        String userId = "usr_" + UUID.randomUUID();
        String operator = "usr_" + UUID.randomUUID();

        dispatcher.dispatch(userAccessAuditedEvent(
            "user.suspended", operator, null, userId, null
        ));

        List<AuditEvent> recorded = events.search(
            new AuditEventQuery(null, "user.suspended", operator, null, 10)
        );

        assertThat(recorded).hasSize(1);
        AuditEvent event = recorded.get(0);
        assertThat(event.actorId()).isEqualTo(operator);
        assertThat(event.merchantId()).isNull();
        assertThat(event.resourceType()).isEqualTo("user");
        assertThat(event.resourceId()).isEqualTo(userId);
    }

    @Test
    void aMerchantScopedUserAccessAuditedEventWritesAnAuditEventRowWithTheMerchant() {
        MerchantId merchantId = MerchantId.generate();
        String userId = "usr_" + UUID.randomUUID();
        String operator = "usr_" + UUID.randomUUID();

        dispatcher.dispatch(userAccessAuditedEvent(
            "user.access_granted", operator, merchantId, userId, "role=MERCHANT_ADMIN"
        ));

        List<AuditEvent> recorded = events.search(
            new AuditEventQuery(merchantId, "user.access_granted", null, null, 10)
        );

        assertThat(recorded).hasSize(1);
        AuditEvent event = recorded.get(0);
        assertThat(event.actorId()).isEqualTo(operator);
        assertThat(event.merchantId()).isEqualTo(merchantId);
        assertThat(event.resourceType()).isEqualTo("user");
        assertThat(event.resourceId()).isEqualTo(userId);
        assertThat(event.reason()).isEqualTo("role=MERCHANT_ADMIN");
    }

    private OutboxEvent userAccessAuditedEvent(
        String action, String operator, MerchantId merchantId, String userId, String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("actorId", operator);
        payload.put("resourceType", "user");
        payload.put("resourceId", userId);
        payload.put("reason", reason);

        return new OutboxEvent(
            EventId.generate(), merchantId, "USER", userId,
            "identity.user_access.audited", 1, payload, OCCURRED_AT
        );
    }
}
