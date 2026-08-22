package com.paymesh.audit.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE HTTP SURFACE OF THE READ, which the service tests cannot see: the platform-admin gate, the
 * filter parameters, and the error mapping only exist once a request travels the filter chain and
 * the advice. The 403 in particular is a real bug this catches -- there is no global advice, so a
 * controller calling {@code requirePlatformAdmin} must map {@code InsufficientRoleException} itself.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class AuditEventControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private final MockMvc mockMvc;
    private final AuditEventRepository events;

    @Autowired
    AuditEventControllerTest(MockMvc mockMvc, AuditEventRepository events) {
        this.mockMvc = mockMvc;
        this.events = events;
    }

    @Test
    void listsEventsForAPlatformAdmin() throws Exception {
        MerchantId merchantId = MerchantId.generate();
        events.append(event("merchant.suspended", "usr_op", merchantId));

        mockMvc.perform(get("/internal/v1/audit-events")
                .param("merchantId", merchantId.value())
                .with(platformAdmin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].action").value("merchant.suspended"))
            .andExpect(jsonPath("$[0].actorType").value("USER"))
            .andExpect(jsonPath("$[0].merchantId").value(merchantId.value()));
    }

    @Test
    void getsOneEventById() throws Exception {
        AuditEvent seeded = events.append(event("webhook.secret_rotated", "usr_op", MerchantId.generate()));

        mockMvc.perform(get("/internal/v1/audit-events/{id}", seeded.id().value())
                .with(platformAdmin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(seeded.id().value()))
            .andExpect(jsonPath("$.action").value("webhook.secret_rotated"));
    }

    /** A merchant token reaches the handler and is refused -- 403, not 500 and not 404. */
    @Test
    void forbidsAMerchantToken() throws Exception {
        mockMvc.perform(get("/internal/v1/audit-events").with(merchantToken("mrc_" + UUID.randomUUID())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(get("/internal/v1/audit-events"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void answers404ForAnUnknownId() throws Exception {
        mockMvc.perform(get("/internal/v1/audit-events/{id}", "aud_" + UUID.randomUUID())
                .with(platformAdmin()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("AUDIT_EVENT_NOT_FOUND"));
    }

    @Test
    void rejectsAMalformedId() throws Exception {
        mockMvc.perform(get("/internal/v1/audit-events/{id}", "not_an_id").with(platformAdmin()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAMalformedMerchantFilter() throws Exception {
        mockMvc.perform(get("/internal/v1/audit-events")
                .param("merchantId", "not_a_merchant")
                .with(platformAdmin()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private static AuditEvent event(String action, String actorId, MerchantId merchantId) {
        return AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, actorId, merchantId,
            action, "merchant", merchantId.value(), null, null, null, null, NOW
        );
    }

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of("PLATFORM_ADMIN")));
    }

    private static RequestPostProcessor merchantToken(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }
}
