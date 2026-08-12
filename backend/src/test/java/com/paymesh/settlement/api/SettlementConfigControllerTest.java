package com.paymesh.settlement.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/settlement-config} -- the one number that decides when a merchant's money becomes
 * settleable (ADR-031).
 * <p>
 * Not {@code @Transactional}: the PUT commits a row that the following GET has to read back, which
 * is the whole point of the round-trip assertions here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SettlementConfigControllerTest {

    /** Platform staff, for the fixture that must activate a merchant. */
    private static final String PLATFORM_OPERATOR = "usr_00000000-0000-4000-8000-000000000001";

    /** {@code paymesh.settlement.default-holding-period: 7d}, in the seconds the API speaks. */
    private static final long DEFAULT_HOLDING_PERIOD_SECONDS = 604_800L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private ChangeMerchantStatusService changeMerchantStatus;

    /**
     * READING THE DEFAULT MUST NOT WRITE A ROW, so the answer has to carry {@code isDefault} --
     * otherwise "never configured" and "configured to exactly the default" are the same response
     * and a later change to the platform default silently skips one of them.
     */
    @Test
    void reportsThePlatformDefaultForAMerchantWhoHasNeverSetOne() throws Exception {
        mockMvc.perform(get("/api/v1/settlement-config").with(callerFor(registerMerchant())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.holdingPeriodSeconds").value(DEFAULT_HOLDING_PERIOD_SECONDS))
            .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void storesTheHoldingPeriodAndStopsCallingItTheDefault() throws Exception {
        MerchantId merchantId = registerMerchant();

        mockMvc.perform(putHoldingPeriod(merchantId, 3600))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.holdingPeriodSeconds").value(3600))
            .andExpect(jsonPath("$.isDefault").value(false));

        mockMvc.perform(get("/api/v1/settlement-config").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.holdingPeriodSeconds").value(3600))
            .andExpect(jsonPath("$.isDefault").value(false));
    }

    /**
     * PUT, AND THE MERCHANT IS THE KEY. A second call replaces the row rather than adding one, so
     * the endpoint is idempotent on its own terms and needs no {@code Idempotency-Key}.
     */
    @Test
    void replacesTheExistingConfigRatherThanWritingASecondRow() throws Exception {
        MerchantId merchantId = registerMerchant();

        mockMvc.perform(putHoldingPeriod(merchantId, 3600)).andExpect(status().isOk());
        mockMvc.perform(putHoldingPeriod(merchantId, 0)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/settlement-config").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.holdingPeriodSeconds").value(0));
    }

    /** A negative period would mean funds cleared before they were captured -- a sign error. */
    @Test
    void rejectsANegativeHoldingPeriod() throws Exception {
        mockMvc.perform(putHoldingPeriod(registerMerchant(), -1))
            .andExpect(status().isBadRequest());
    }

    /**
     * Bounded above at a year, because a period longer than that is far more likely to be days
     * typed as seconds than an intention -- and that mistake holds money nobody notices.
     */
    @Test
    void rejectsAHoldingPeriodBeyondAYear() throws Exception {
        mockMvc.perform(putHoldingPeriod(registerMerchant(), 31_536_001L))
            .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/v1/settlement-config"))
            .andExpect(status().isUnauthorized());
    }

    /**
     * TENANT ISOLATION, AND THE REQUEST CANNOT EVEN EXPRESS THE VIOLATION. The merchant comes from
     * the verified token, so another merchant's write is invisible here rather than forbidden.
     */
    @Test
    void neverShowsOneMerchantAnotherMerchantsHoldingPeriod() throws Exception {
        mockMvc.perform(putHoldingPeriod(registerMerchant(), 0)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/settlement-config").with(callerFor(registerMerchant())))
            .andExpect(jsonPath("$.holdingPeriodSeconds").value(DEFAULT_HOLDING_PERIOD_SECONDS))
            .andExpect(jsonPath("$.isDefault").value(true));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
        putHoldingPeriod(MerchantId merchantId, long seconds) {

        return put("/api/v1/settlement-config")
            .with(callerFor(merchantId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holdingPeriodSeconds\": " + seconds + "}");
    }

    private static RequestPostProcessor callerFor(MerchantId merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId.value())));
    }

    private MerchantId registerMerchant() {
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Settlement Config Test Co", "settlement-" + UUID.randomUUID() + "@example.test",
            "IN", "INR"
        )).merchantId();

        changeMerchantStatus.activate(merchantId, PLATFORM_OPERATOR, "Activated for test");

        return merchantId;
    }
}
