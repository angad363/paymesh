package com.paymesh.audit;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.audit.application.AuditEventQuery;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.shared.audit.ActorType;
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
 * PROVES THE MONOLITH SIDE OF ADR-043 SECTION 5: a {@code merchant.status_changed.audited}
 * envelope, exactly the shape {@code ChangeMerchantStatusService}'s own outbox now produces
 * (Merchant is still in the monolith), turns into the same {@code audit_events} row the in-process
 * {@code AuditRecorder.record} call used to write directly -- before Audit left this process.
 *
 * <p>{@code ChangeMerchantStatusService} cannot be driven from here any more (Merchant is a
 * different deployable's capability); this test starts one level below that boundary, at the shape
 * the two sides agree on: an {@link OutboxEvent} of this exact type and payload, dispatched through
 * the SAME {@link EventDispatcher} every other consumer in this process shares. What it proves is
 * that {@code RecordMerchantStatusChangeAuditHandler} is genuinely wired in and genuinely
 * reconstructs the entry the old in-process call built, not merely that the class compiles.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AuditRecordingIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-20T10:00:00Z");

    private final EventDispatcher dispatcher;
    private final AuditEventRepository events;

    @Autowired
    AuditRecordingIntegrationTest(EventDispatcher dispatcher, AuditEventRepository events) {
        this.dispatcher = dispatcher;
        this.events = events;
    }

    @Test
    void aMerchantStatusChangedAuditedEventWritesTheAuditEventsRowWithHashedBeforeAfter() {
        MerchantId merchantId = MerchantId.generate();
        String operator = "usr_" + UUID.randomUUID();

        dispatcher.dispatch(statusChangedAuditedEvent(merchantId, operator, "fraud investigation"));

        List<AuditEvent> recorded = events.search(
            new AuditEventQuery(merchantId, "merchant.suspended", null, null, 10)
        );

        assertThat(recorded).hasSize(1);
        AuditEvent event = recorded.get(0);
        assertThat(event.actorType()).isEqualTo(ActorType.USER);
        assertThat(event.actorId()).isEqualTo(operator);
        assertThat(event.merchantId()).isEqualTo(merchantId);
        assertThat(event.resourceType()).isEqualTo("merchant");
        assertThat(event.resourceId()).isEqualTo(merchantId.value());
        assertThat(event.reason()).isEqualTo("fraud investigation");
        // before/after are stored HASHED, never the plaintext status values the event payload
        // carried in the clear.
        assertThat(event.beforeHash()).isNotNull().isNotEqualTo("ACTIVE").hasSize(64);
        assertThat(event.afterHash()).isNotNull().isNotEqualTo("SUSPENDED").hasSize(64);
    }

    private OutboxEvent statusChangedAuditedEvent(MerchantId merchantId, String operator, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "merchant.suspended");
        payload.put("actorId", operator);
        payload.put("resourceType", "merchant");
        payload.put("resourceId", merchantId.value());
        payload.put("reason", reason);
        payload.put("before", "ACTIVE");
        payload.put("after", "SUSPENDED");

        return new OutboxEvent(
            EventId.generate(), merchantId, "MERCHANT", merchantId.value(),
            "merchant.status_changed.audited", 1, payload, OCCURRED_AT
        );
    }
}
