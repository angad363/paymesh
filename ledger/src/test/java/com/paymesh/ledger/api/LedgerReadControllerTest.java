package com.paymesh.ledger.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.LedgerTestEvents;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ledger read the settlement service cuts from (ADR-047 section 1a): shared-key guarded. */
@SpringBootTest(properties = "paymesh.ledger.release.enabled=true")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LedgerReadControllerTest {

    private static final String KEY_HEADER = "X-PayMesh-Ledger-Key";
    private static final String DEV_KEY = "dev-only-insecure-ledger-read-key-change-me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private com.paymesh.ledger.infrastructure.schedule.AvailableFundsReleaseSweeper sweeper;

    @Test
    void refusesACallWithoutTheKeyAndWithAWrongKey() throws Exception {
        String path = "/internal/v1/ledger/merchants/" + MerchantId.generate().value()
            + "/available-contributions";

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header(KEY_HEADER, "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/v1/ledger/available-merchants")).andExpect(status().isUnauthorized());
    }

    /** A malformed merchantId is a caller error: 400, not the unmapped 500 it was before. */
    @Test
    void rejectsAMalformedMerchantIdWith400() throws Exception {
        mockMvc.perform(get("/internal/v1/ledger/merchants/not-a-valid-id/available-contributions")
                .header(KEY_HEADER, DEV_KEY))
            .andExpect(status().isBadRequest());
    }

    @Test
    void returnsTheMerchantsPerPaymentAvailableContributions() throws Exception {
        MerchantId merchantId = MerchantId.generate();
        String paymentIntentId = LedgerTestEvents.newPaymentIntentId();
        dispatcher.dispatch(LedgerTestEvents.paymentSucceeded(merchantId, paymentIntentId, 7_000L));
        sweeper.release();

        mockMvc.perform(get("/internal/v1/ledger/merchants/" + merchantId.value()
                + "/available-contributions").header(KEY_HEADER, DEV_KEY))
            .andExpect(status().isOk())
                        .andExpect(jsonPath("$.contributions[0].paymentIntentId").value(paymentIntentId))
            .andExpect(jsonPath("$.contributions[0].amountMinor").value(7000));

        mockMvc.perform(get("/internal/v1/ledger/available-merchants").header(KEY_HEADER, DEV_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merchantIds[?(@ == '" + merchantId.value() + "')]").exists());
    }
}
