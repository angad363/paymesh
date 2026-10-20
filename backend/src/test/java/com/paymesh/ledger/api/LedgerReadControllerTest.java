package com.paymesh.ledger.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.ledger.application.ReleaseAvailableFundsService;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ledger read the settlement service cuts from (ADR-047 section 1a): shared-key guarded. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LedgerReadControllerTest {

    private static final String KEY_HEADER = "X-PayMesh-Ledger-Key";
    private static final String DEV_KEY = "dev-only-insecure-ledger-read-key-change-me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostPaymentCapturedService postCapture;

    @Autowired
    private ReleaseAvailableFundsService release;

    @Autowired
    private GetSettlementConfigService settlementConfigs;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void refusesACallWithoutTheKeyAndWithAWrongKey() throws Exception {
        String path = "/internal/v1/ledger/merchants/" + MerchantId.generate().value()
            + "/available-contributions";

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header(KEY_HEADER, "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/v1/ledger/available-merchants")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheMerchantsPerPaymentAvailableContributions() throws Exception {
        Instant at = Instant.parse("2026-08-01T10:00:00Z");
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(), "Ledger Read Co", UUID.randomUUID() + "@paymesh.test", "IN", "INR", at
        ).activate(at)).merchantId();
        settlementConfigs.set(merchantId, Duration.ZERO, "acct_x", 1L);
        String paymentIntentId = "pi_" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status ->
            postCapture.post(merchantId, paymentIntentId, 7_000L, "INR", at)
        );
        release.release();

        mockMvc.perform(get("/internal/v1/ledger/merchants/" + merchantId.value()
                + "/available-contributions").header(KEY_HEADER, DEV_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributions[0].currency").value("INR"))
            .andExpect(jsonPath("$.contributions[0].paymentIntentId").value(paymentIntentId))
            .andExpect(jsonPath("$.contributions[0].amountMinor").value(7000));

        mockMvc.perform(get("/internal/v1/ledger/available-merchants").header(KEY_HEADER, DEV_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merchantIds[?(@ == '" + merchantId.value() + "')]").exists());
    }
}
