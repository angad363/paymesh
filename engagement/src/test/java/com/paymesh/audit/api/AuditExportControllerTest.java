package com.paymesh.audit.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.application.GenerateAuditExportsService;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE HTTP SURFACE OF THE AUDIT EXPORT: the platform-admin gate, the 202-with-Location, the
 * content-negotiated {@code GET .../{id}} and the 409-not-ready. Structurally the same as
 * {@code ReportExportControllerTest}, with a platform-admin caller rather than a merchant one.
 *
 * <p>NOT {@code @Transactional}: the generator opens its own transaction, and the request row must
 * be committed before the generator can claim it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class AuditExportControllerTest {

    private final MockMvc mockMvc;
    private final AuditEventRepository events;
    private final GenerateAuditExportsService generate;

    @Autowired
    AuditExportControllerTest(
        MockMvc mockMvc, AuditEventRepository events, GenerateAuditExportsService generate
    ) {
        this.mockMvc = mockMvc;
        this.events = events;
        this.generate = generate;
    }

    @Test
    void acceptsAnExportRequestWith202AndALocation() throws Exception {
        mockMvc.perform(post("/internal/v1/audit-exports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(platformAdmin()))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("aex_")))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.requestedBy").value(org.hamcrest.Matchers.startsWith("usr_")));
    }

    /** The same id answers JSON metadata by default and the CSV under {@code Accept: text/csv}. */
    @Test
    void servesJsonByDefaultAndCsvOnRequest() throws Exception {
        events.append(event("merchant.suspended", "usr_op", MerchantId.generate()));

        String id = requestExport();
        generate.generate();

        mockMvc.perform(get("/internal/v1/audit-exports/{id}", id).with(platformAdmin()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("json")))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.rowCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        MvcResult csv = mockMvc.perform(get("/internal/v1/audit-exports/{id}", id)
                .accept("text/csv")
                .with(platformAdmin()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
            .andExpect(header().string(
                "Content-Disposition", org.hamcrest.Matchers.containsString(id + ".csv")))
            .andReturn();

        assertThat(csv.getResponse().getContentAsString())
            .startsWith("auditEventId,occurredAt")
            .contains("merchant.suspended");
    }

    /** Asking for the CSV before it is rendered is a 409, not a 404: keep polling. */
    @Test
    void refusesTheCsvBeforeItIsReady() throws Exception {
        String id = requestExport();

        mockMvc.perform(get("/internal/v1/audit-exports/{id}", id)
                .accept("text/csv")
                .with(platformAdmin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUDIT_EXPORT_NOT_READY"));
    }

    /** A merchant token is refused at both surfaces -- 403, not 500. */
    @Test
    void forbidsAMerchantToken() throws Exception {
        mockMvc.perform(post("/internal/v1/audit-exports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(merchantToken("mrc_" + UUID.randomUUID())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void answers404ForAnUnknownExport() throws Exception {
        mockMvc.perform(get("/internal/v1/audit-exports/{id}", "aex_" + UUID.randomUUID())
                .with(platformAdmin()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("AUDIT_EXPORT_NOT_FOUND"));
    }

    @Test
    void rejectsAMalformedExportId() throws Exception {
        mockMvc.perform(get("/internal/v1/audit-exports/{id}", "not-an-id").with(platformAdmin()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String requestExport() throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/v1/audit-exports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(platformAdmin()))
            .andExpect(status().isAccepted())
            .andReturn();

        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static AuditEvent event(String action, String actorId, MerchantId merchantId) {
        return AuditEvent.record(
            AuditEventId.generate(), ActorType.USER, actorId, merchantId,
            action, "merchant", merchantId.value(), null, null, null, null, Instant.now()
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
