package com.paymesh.merchant;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ADR-039 verification, as the plan of record states it: register -> activate propagates to the
 * projection and a scoped write succeeds; a not-yet-propagated merchant yields a RETRYABLE error, not
 * a 500; a suspension, once propagated, denies.
 *
 * <p>Every other test drives the relay in its fixture to AVOID the not-yet-propagated window. This
 * one exercises the window on purpose -- it is the new, load-bearing behavior of PR 4.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MerchantRefProjectionIntegrationTest {

    private static final String OPERATOR = "usr_00000000-0000-4000-8000-000000000001";

    private final MockMvc mockMvc;
    private final RegisterMerchantService merchants;
    private final ChangeMerchantStatusService changeMerchantStatus;
    private final PublishOutboxEventsService relay;

    @Autowired
    MerchantRefProjectionIntegrationTest(
        MockMvc mockMvc,
        RegisterMerchantService merchants,
        ChangeMerchantStatusService changeMerchantStatus,
        PublishOutboxEventsService relay
    ) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
        this.changeMerchantStatus = changeMerchantStatus;
        this.relay = relay;
    }

    /**
     * THE HEADLINE. A merchant is registered and activated but the lifecycle events have NOT been
     * relayed, so the projection is empty. The gate must answer UNKNOWN, and the write must be
     * refused with a retryable 503 -- not a 403 (which reads as a permanent decision) and, above all,
     * not a 500.
     */
    @Test
    void aNotYetPropagatedMerchantYieldsARetryable503NotA500() throws Exception {
        MerchantId merchantId = registerAndActivateWithoutPropagating();

        mockMvc.perform(newOrder(merchantId))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.code").value("MERCHANT_NOT_YET_AVAILABLE"));
    }

    /** Once the events are relayed into the projection, the same scoped write succeeds. */
    @Test
    void oncePropagatedTheScopedWriteSucceeds() throws Exception {
        MerchantId merchantId = registerAndActivateWithoutPropagating();

        relay.publish();

        mockMvc.perform(newOrder(merchantId)).andExpect(status().isCreated());
    }

    /** A registered-but-unverified merchant, once projected, is DENIED (403), not UNKNOWN (503). */
    @Test
    void aProjectedUnverifiedMerchantIsDeniedNotUnknown() throws Exception {
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Pending Co", "pending-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();

        relay.publish();

        mockMvc.perform(newOrder(merchantId))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MERCHANT_NOT_ACTIVE"));
    }

    /** A suspension, once propagated, flips the gate from ALLOWED to DENIED. */
    @Test
    void aPropagatedSuspensionDeniesTheNextWrite() throws Exception {
        MerchantId merchantId = registerAndActivateWithoutPropagating();
        relay.publish();

        mockMvc.perform(newOrder(merchantId)).andExpect(status().isCreated());

        changeMerchantStatus.suspend(merchantId, OPERATOR, "Suspected fraud");
        relay.publish();

        mockMvc.perform(newOrder(merchantId))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MERCHANT_NOT_ACTIVE"));
    }

    private MerchantId registerAndActivateWithoutPropagating() {
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Projection Co", "proj-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();
        changeMerchantStatus.activate(merchantId, OPERATOR, "Activated for test");
        return merchantId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder newOrder(
        MerchantId merchantId
    ) {
        return post("/api/v1/orders")
            .with(callerFor(merchantId.value()))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"merchantOrderReference\": \"REF-" + UUID.randomUUID() + "\", "
                + "\"amountMinor\": 500, \"currency\": \"INR\"}");
    }

    private static RequestPostProcessor callerFor(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }
}
