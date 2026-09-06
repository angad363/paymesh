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
 * PROVES THE MONOLITH SIDE OF ADR-042 SECTION 4: a {@code webhook.secret_rotated.audited} envelope,
 * exactly the shape webhook's own outbox now produces, turns into the same {@code audit_events} row
 * the in-process {@code RotateWebhookSecretService} call used to write directly -- before that
 * capability left this process.
 *
 * <p>Webhook itself cannot be driven from here any more (it is a separate deployable, ADR-042); this
 * test starts one level below that boundary, at the shape the two sides agree on: an
 * {@link OutboxEvent} of this exact type and payload, dispatched through the SAME
 * {@link EventDispatcher} every other consumer in this process shares. What it proves is that
 * {@code RecordWebhookSecretRotationAuditHandler} is genuinely wired in and genuinely reconstructs
 * the entry the old in-process call built, not merely that the class compiles.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class RecordWebhookSecretRotationAuditHandlerIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-06T10:00:00Z");

    private final EventDispatcher dispatcher;
    private final AuditEventRepository events;

    @Autowired
    RecordWebhookSecretRotationAuditHandlerIntegrationTest(
        EventDispatcher dispatcher, AuditEventRepository events
    ) {
        this.dispatcher = dispatcher;
        this.events = events;
    }

    @Test
    void aSecretRotatedAuditedEventWritesTheAuditEventsRowWithHashedBeforeAfter() {
        MerchantId merchantId = MerchantId.generate();
        String endpointId = "whe_" + UUID.randomUUID();
        String operatorId = "usr_" + UUID.randomUUID();

        dispatcher.dispatch(secretRotatedAuditedEvent(merchantId, endpointId, operatorId));

        List<AuditEvent> recorded = events.search(
            new AuditEventQuery(merchantId, "webhook.secret_rotated", null, null, 10)
        );

        assertThat(recorded).hasSize(1);
        AuditEvent event = recorded.get(0);
        assertThat(event.actorId()).isEqualTo(operatorId);
        assertThat(event.merchantId()).isEqualTo(merchantId);
        assertThat(event.resourceType()).isEqualTo("webhook_endpoint");
        assertThat(event.resourceId()).isEqualTo(endpointId);
        // NOT an audit_events row webhook itself wrote -- it cannot; this asserts it landed hashed,
        // never as the plaintext version strings the event payload carried.
        assertThat(event.beforeHash()).isNotNull().isNotEqualTo("v1").hasSize(64);
        assertThat(event.afterHash()).isNotNull().isNotEqualTo("v2").hasSize(64);
    }

    private OutboxEvent secretRotatedAuditedEvent(
        MerchantId merchantId, String endpointId, String operatorId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchantId.value());
        payload.put("endpointId", endpointId);
        payload.put("operatorId", operatorId);
        payload.put("before", "v1");
        payload.put("after", "v2");

        return new OutboxEvent(
            EventId.generate(), merchantId, "WEBHOOK_ENDPOINT", endpointId,
            "webhook.secret_rotated.audited", 1, payload, OCCURRED_AT
        );
    }
}
