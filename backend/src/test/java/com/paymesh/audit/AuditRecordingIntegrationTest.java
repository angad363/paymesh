package com.paymesh.audit;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.audit.application.AuditEventQuery;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROVES THE PORT END TO END FROM A REAL CALL SITE. The capability's own tests use the recorder
 * directly; this drives a genuine privileged action -- a merchant suspension -- through
 * {@code ChangeMerchantStatusService} and asserts the audit row appears, committed in the same
 * transaction as the status change (ADR-035).
 *
 * <p>A recorder wired to nothing, or a call site that forgot to record, passes every other test in
 * this module and fails this one. That is the point: the audit log's value is that the privileged
 * actions actually reach it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AuditRecordingIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private final ChangeMerchantStatusService changeStatus;
    private final MerchantRepository merchants;
    private final AuditEventRepository events;

    @Autowired
    AuditRecordingIntegrationTest(
        ChangeMerchantStatusService changeStatus,
        MerchantRepository merchants,
        AuditEventRepository events
    ) {
        this.changeStatus = changeStatus;
        this.merchants = merchants;
        this.events = events;
    }

    @Test
    void suspendingAMerchantWritesAnAuditEvent() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(), "Paymesh Audit Co",
            UUID.randomUUID() + "@paymesh.test", "IN", "INR", NOW
        ).activate(NOW)).merchantId();

        String operator = "usr_" + UUID.randomUUID();

        changeStatus.suspend(merchantId, operator, "fraud investigation");

        List<AuditEvent> recorded = events.search(
            new AuditEventQuery(merchantId, "merchant.suspended", null, null, 10)
        );

        assertThat(recorded).hasSize(1);
        AuditEvent event = recorded.get(0);
        assertThat(event.actorType()).isEqualTo(ActorType.USER);
        assertThat(event.actorId()).isEqualTo(operator);
        assertThat(event.resourceType()).isEqualTo("merchant");
        assertThat(event.resourceId()).isEqualTo(merchantId.value());
        assertThat(event.reason()).isEqualTo("fraud investigation");
        // before/after are stored HASHED, never the plaintext status values.
        assertThat(event.beforeHash())
            .isNotNull()
            .isNotEqualTo("ACTIVE")
            .hasSize(64);
        assertThat(event.afterHash()).isNotNull().isNotEqualTo("SUSPENDED").hasSize(64);
    }
}
