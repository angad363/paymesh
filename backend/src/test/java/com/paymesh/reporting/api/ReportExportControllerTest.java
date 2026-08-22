package com.paymesh.reporting.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.reporting.application.GenerateReportExportsService;
import com.paymesh.reporting.application.RecordReportFactService;
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
 * THE HTTP SURFACE OF THE EXPORT, and the one route this capability adds that MockMvc sees
 * differently from a real client: content negotiation. The same {@code GET .../{id}} answers JSON
 * or CSV by {@code Accept}, and the 409-not-ready and the 202-with-Location only exist over HTTP.
 *
 * <p>NOT {@code @Transactional}, deliberately: the generator opens its own transaction, and an
 * outer test transaction would swallow it -- and the request row must be committed before the
 * generator can claim it in a separate transaction. Each test registers its own merchant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class ReportExportControllerTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-16T10:00:00Z");

    private final MockMvc mockMvc;
    private final MerchantRepository merchants;
    private final RecordReportFactService record;
    private final GenerateReportExportsService generate;

    @Autowired
    ReportExportControllerTest(
        MockMvc mockMvc,
        MerchantRepository merchants,
        RecordReportFactService record,
        GenerateReportExportsService generate
    ) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
        this.record = record;
        this.generate = generate;
    }

    @Test
    void acceptsAnExportRequestWith202AndALocation() throws Exception {
        MerchantId merchantId = seedMerchant();

        mockMvc.perform(
                post("/api/v1/report-exports")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(merchant(merchantId.value()))
            )
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("rex_")))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /**
     * THE ROUTE THIS PR ADDS. The same id answers JSON metadata by default and the CSV under
     * {@code Accept: text/csv}. A single {@code /{id}} route with two representations is what
     * content negotiation is for.
     */
    @Test
    void servesJsonByDefaultAndCsvOnRequest() throws Exception {
        MerchantId merchantId = seedMerchant();
        record.record(
            merchantId, "evt_" + UUID.randomUUID(), "payment.succeeded",
            "pi_" + UUID.randomUUID(), "ord_" + UUID.randomUUID(), "USD", 12_500, OCCURRED
        );

        String id = requestExport(merchantId);
        generate.generate();

        // Default representation: JSON metadata.
        mockMvc.perform(
                get("/api/v1/report-exports/{id}", id).with(merchant(merchantId.value()))
            )
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("json")))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.rowCount").value(1));

        // CSV representation: the file, as a download.
        MvcResult csv = mockMvc.perform(
                get("/api/v1/report-exports/{id}", id)
                    .accept("text/csv")
                    .with(merchant(merchantId.value()))
            )
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
            .andExpect(header().string(
                "Content-Disposition", org.hamcrest.Matchers.containsString(id + ".csv")))
            .andReturn();

        assertThat(csv.getResponse().getContentAsString())
            .startsWith("occurredAt,eventType")
            .contains("payment.succeeded");
    }

    /** Asking for the CSV before it is rendered is a 409, not a 404: keep polling. */
    @Test
    void refusesTheCsvBeforeItIsReady() throws Exception {
        MerchantId merchantId = seedMerchant();
        String id = requestExport(merchantId);

        mockMvc.perform(
                get("/api/v1/report-exports/{id}", id)
                    .accept("text/csv")
                    .with(merchant(merchantId.value()))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REPORT_EXPORT_NOT_READY"));
    }

    /** Another tenant's export id is a 404, indistinguishable from one that never existed. */
    @Test
    void hidesAnotherTenantsExport() throws Exception {
        MerchantId mine = seedMerchant();
        MerchantId theirs = seedMerchant();
        String theirExport = requestExport(theirs);

        mockMvc.perform(
                get("/api/v1/report-exports/{id}", theirExport).with(merchant(mine.value()))
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPORT_EXPORT_NOT_FOUND"));
    }

    /** A retried POST on one key replays the first response rather than making a second export. */
    @Test
    void replaysAnIdempotentRequest() throws Exception {
        MerchantId merchantId = seedMerchant();
        String key = UUID.randomUUID().toString();

        String first = requestExport(merchantId, key);
        String second = requestExport(merchantId, key);

        assertThat(second).isEqualTo(first);
    }

    /** The POST is a registered idempotent route, so the key is mandatory. */
    @Test
    void requiresAnIdempotencyKey() throws Exception {
        mockMvc.perform(
                post("/api/v1/report-exports")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(merchant(seedMerchant().value()))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMalformedExportId() throws Exception {
        mockMvc.perform(
                get("/api/v1/report-exports/{id}", "not-an-id").with(merchant(seedMerchant().value()))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- helpers --------------------------------------------------------------------------------

    private String requestExport(MerchantId merchantId) throws Exception {
        return requestExport(merchantId, UUID.randomUUID().toString());
    }

    private String requestExport(MerchantId merchantId, String key) throws Exception {
        MvcResult result = mockMvc.perform(
                post("/api/v1/report-exports")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(merchant(merchantId.value()))
            )
            .andExpect(status().isAccepted())
            .andReturn();

        return com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.id"
        );
    }

    private MerchantId seedMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(), "Paymesh Export Co",
            UUID.randomUUID() + "@paymesh.test", "US", "USD", OCCURRED
        ).activate(OCCURRED)).merchantId();
    }

    private static RequestPostProcessor merchant(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }
}
