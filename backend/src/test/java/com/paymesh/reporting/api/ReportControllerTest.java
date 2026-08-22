package com.paymesh.reporting.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.reporting.application.RecordReportFactService;
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
 * THE HTTP SURFACE OF THE TWO READ REPORTS, which the service-level tests cannot see: the routing,
 * the tenant derivation, the query-parameter parsing, the {@code asOf} field and the error mapping
 * only exist once a request travels the filter chain and the advice. Open item 16 is the standing
 * proof that a green Java suite can sit over an unusable HTTP surface.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class ReportControllerTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-16T10:00:00Z");

    private final MockMvc mockMvc;
    private final MerchantRepository merchants;
    private final RecordReportFactService record;

    @Autowired
    ReportControllerTest(
        MockMvc mockMvc, MerchantRepository merchants, RecordReportFactService record
    ) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
        this.record = record;
    }

    @Test
    void returnsAPaymentSummaryScopedToTheCaller() throws Exception {
        MerchantId merchantId = seedMerchant();
        seedSucceeded(merchantId, 12_500);

        mockMvc.perform(
                get("/api/v1/reports/payment-summary")
                    .param("from", "2026-08-01T00:00:00Z")
                    .param("to", "2026-09-01T00:00:00Z")
                    .with(merchant(merchantId.value()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.asOf").isNotEmpty())
            .andExpect(jsonPath("$.currencies[0].currency").value("USD"))
            .andExpect(jsonPath("$.currencies[0].succeededCount").value(1))
            .andExpect(jsonPath("$.currencies[0].succeededAmountMinor").value(12_500));
    }

    /** A merchant with no facts gets an empty report and a null asOf, not a fabricated one. */
    @Test
    void reportsAnHonestlyEmptySummary() throws Exception {
        mockMvc.perform(
                get("/api/v1/reports/payment-summary").with(merchant(seedMerchant().value()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.asOf").doesNotExist())
            .andExpect(jsonPath("$.currencies").isEmpty());
    }

    /** One merchant's numbers never appear in another's report -- the leak reads as a real total. */
    @Test
    void doesNotLeakAnotherTenantsFacts() throws Exception {
        MerchantId mine = seedMerchant();
        MerchantId theirs = seedMerchant();
        seedSucceeded(theirs, 99_900);

        mockMvc.perform(
                get("/api/v1/reports/payment-summary").with(merchant(mine.value()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencies").isEmpty());
    }

    @Test
    void returnsASettlementSummary() throws Exception {
        MerchantId merchantId = seedMerchant();
        record.record(
            merchantId, "evt_" + UUID.randomUUID(), "settlement.batch_cut",
            "stl_" + UUID.randomUUID(), null, "USD", 90_000, OCCURRED
        );

        mockMvc.perform(
                get("/api/v1/reports/settlements").with(merchant(merchantId.value()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencies[0].batchesCut").value(1))
            .andExpect(jsonPath("$.currencies[0].cutAmountMinor").value(90_000));
    }

    /** A window the caller wrote wrong is a 400 -- their mistake, not a 500. */
    @Test
    void rejectsAMalformedWindow() throws Exception {
        mockMvc.perform(
                get("/api/v1/reports/payment-summary")
                    .param("from", "not-an-instant")
                    .with(merchant(seedMerchant().value()))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** A window longer than the domain allows is refused before it can scan the table. */
    @Test
    void rejectsAWindowLongerThanTheCap() throws Exception {
        mockMvc.perform(
                get("/api/v1/reports/payment-summary")
                    .param("from", "2020-01-01T00:00:00Z")
                    .param("to", "2026-01-01T00:00:00Z")
                    .with(merchant(seedMerchant().value()))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** Platform staff hold no merchant scope, so a merchant report has nothing to scope to: 403. */
    @Test
    void forbidsAPlatformToken() throws Exception {
        mockMvc.perform(
                get("/api/v1/reports/payment-summary").with(platformAdmin())
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/reports/payment-summary"))
            .andExpect(status().isUnauthorized());
    }

    // --- helpers --------------------------------------------------------------------------------

    private MerchantId seedMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(), "Paymesh Reporting Co",
            UUID.randomUUID() + "@paymesh.test", "US", "USD", OCCURRED
        ).activate(OCCURRED)).merchantId();
    }

    private void seedSucceeded(MerchantId merchantId, long amountMinor) {
        record.record(
            merchantId, "evt_" + UUID.randomUUID(), "payment.succeeded",
            "pi_" + UUID.randomUUID(), "ord_" + UUID.randomUUID(), "USD", amountMinor, OCCURRED
        );
    }

    private static RequestPostProcessor merchant(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of("PLATFORM_ADMIN")));
    }
}
