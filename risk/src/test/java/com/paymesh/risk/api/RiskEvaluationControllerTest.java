package com.paymesh.risk.api;

import com.paymesh.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE SHARED KEY IS THE AUTHENTICATION, and this class is what says so -- the same shape
 * {@code SimulatorApiTest} proves for {@code /sim/v1/**}. This deployable carries no Spring
 * Security chain at all: {@code RiskEvaluationKeyFilter} is the only thing standing in front of
 * {@code /internal/v1/risk-evaluations}.
 * <p>
 * Nothing here mints a JWT or names a caller identity, and that absence is the point -- Payment's
 * confirm path authenticates as a service, not as a merchant or a human.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RiskEvaluationControllerTest {

    private static final String EVALUATIONS = "/internal/v1/risk-evaluations";
    private static final String KEY_HEADER = "X-PayMesh-Risk-Key";

    /** application-dev.yaml's value. Public by definition; a real deployment refuses it. */
    private static final String DEV_KEY = "dev-only-insecure-risk-evaluate-key-change-me";

    @Autowired
    private MockMvc mockMvc;

    // ---------------------------------------------------------------- the key

    @Test
    void refusesARequestCarryingNoRiskKey() throws Exception {
        mockMvc.perform(post(EVALUATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("RISK_KEY_INVALID"));
    }

    @Test
    void refusesARequestCarryingTheWrongRiskKey() throws Exception {
        mockMvc.perform(post(EVALUATIONS)
                .header(KEY_HEADER, "not-the-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("RISK_KEY_INVALID"));
    }

    // ---------------------------------------------------------------- the decision

    @Test
    void allowsAnOrdinaryPaymentAndReturnsAnAssessmentId() throws Exception {
        mockMvc.perform(post(EVALUATIONS)
                .header(KEY_HEADER, DEV_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permitted").value(true))
            .andExpect(jsonPath("$.assessmentId").isNotEmpty());
    }

    @Test
    void rejectsAMalformedMerchantIdWithABadRequest() throws Exception {
        mockMvc.perform(post(EVALUATIONS)
                .header(KEY_HEADER, DEV_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"merchantId":"not-a-merchant-id","paymentIntentId":"pi_%s",
                     "amountMinor":1000,"currency":"INR"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private static String requestBody() {
        return """
            {"merchantId":"mrc_%s","paymentIntentId":"pi_%s",
             "amountMinor":1999,"currency":"INR","customerId":null,"device":"device-1"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID());
    }
}
