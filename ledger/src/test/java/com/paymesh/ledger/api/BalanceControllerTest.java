package com.paymesh.ledger.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.LedgerTestEvents;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/balances} against the ledger service (JWT-verified at the edge, ADR-040). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class BalanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventDispatcher dispatcher;

    @Test
    void returnsTheBalanceInIntegerMinorUnits() throws Exception {
        MerchantId merchantId = collect(MerchantId.generate(), 99900, "INR");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances.length()").value(1))
            .andExpect(jsonPath("$.balances[0].currency").value("INR"))
            .andExpect(jsonPath("$.balances[0].pendingMinor").value(99900))
            .andExpect(jsonPath("$.balances[0].pendingMinor").isNumber())
            .andExpect(jsonPath("$.balances[0].availableMinor").value(0))
            .andExpect(jsonPath("$.balances[0].inSettlementMinor").value(0))
            .andExpect(jsonPath("$.balances[0].reservedMinor").doesNotExist());
    }

    @Test
    void returnsAnEmptyListForAMerchantWithNoPayments() throws Exception {
        mockMvc.perform(get("/api/v1/balances").with(callerFor(MerchantId.generate())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances.length()").value(0));
    }

    /** The tenant comes from the verified token; there is no merchant parameter to tamper with. */
    @Test
    void neverShowsOneMerchantAnotherMerchantsMoney() throws Exception {
        collect(MerchantId.generate(), 99900, "INR");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(MerchantId.generate())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances.length()").value(0));
    }

    @Test
    void returnsOneRowPerCurrency() throws Exception {
        MerchantId merchantId = collect(MerchantId.generate(), 99900, "INR");
        collect(merchantId, 4200, "USD");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.balances.length()").value(2))
            .andExpect(jsonPath("$.balances[0].currency").value("INR"))
            .andExpect(jsonPath("$.balances[1].currency").value("USD"));
    }

    @Test
    void refusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/v1/balances")).andExpect(status().isUnauthorized());
    }

    @Test
    void refusesACallerWithNoMerchantScope() throws Exception {
        RequestPostProcessor scopeless = jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of()));

        mockMvc.perform(get("/api/v1/balances").with(scopeless))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    private MerchantId collect(MerchantId merchantId, long amountMinor, String currency) {
        dispatcher.dispatch(LedgerTestEvents.paymentSucceeded(
            EventId.generate(), merchantId, LedgerTestEvents.newPaymentIntentId(), amountMinor, currency
        ));

        return merchantId;
    }

    private static RequestPostProcessor callerFor(MerchantId merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId.value())));
    }
}
