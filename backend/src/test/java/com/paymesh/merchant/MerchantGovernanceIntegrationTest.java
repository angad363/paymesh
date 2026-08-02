package com.paymesh.merchant;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE GATE, THE KEY, AND THE CONTROL, against a real PostgreSQL.
 * <p>
 * Three things the Phase 1 audit found missing and this proves are present: a merchant cannot
 * transact before it is verified, KYC approval is what verifies it, and a merchant can be stopped
 * afterwards.
 * <p>
 * The first test is the one that matters most. When the status gate was built without KYC, 84 tests
 * failed with 403 where 201 was expected -- a lock with no key. This asserts the key exists.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MerchantGovernanceIntegrationTest {

    private static final String OPERATOR = "usr_00000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private ChangeMerchantStatusService changeMerchantStatus;

    @Autowired
    private JdbcClient jdbc;

    /**
     * THE WHOLE LIFECYCLE: register, blocked, submit, approved, transacting.
     * <p>
     * <b>Sabotage that must turn this red:</b> exempt nothing in {@code MerchantStatusFilter}, or
     * stop KYC approval activating the merchant. Either way the merchant never escapes
     * PENDING_VERIFICATION and the order at the end fails with 403.
     */
    @Test
    void takesAMerchantFromRegisteredToTransactingThroughKyc() throws Exception {
        MerchantId merchantId = register();

        // 1. A newly registered merchant is refused every write.
        mockMvc.perform(newOrder(merchantId))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MERCHANT_NOT_ACTIVE"));

        // 2. Except submitting verification, which is exempt -- refusing it would make ACTIVE
        //    unreachable, which is the deadlock rather than the fix.
        String submissionId = mockMvc.perform(post(
                "/api/v1/merchants/" + merchantId.value() + "/kyc-submissions")
                .with(merchantAdmin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "legalName": "Test Co Pvt Ltd", "registrationId": "U74999KA2020PTC000001" }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"id\":\"(kyc_[^\"]+)\".*", "$1");

        // 3. Platform staff approve, which activates the merchant in the same transaction.
        mockMvc.perform(post("/api/v1/merchants/kyc-submissions/" + submissionId + "/approve")
                .with(platformAdmin())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.reviewedBy").value(OPERATOR));

        // 4. And now it can trade.
        mockMvc.perform(newOrder(merchantId)).andExpect(status().isCreated());
    }

    /** THE CONTROL: an active merchant can be stopped, and stops immediately. */
    @Test
    void stopsASuspendedMerchantWriting() throws Exception {
        MerchantId merchantId = activated();

        mockMvc.perform(newOrder(merchantId)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/merchants/" + merchantId.value() + "/suspend")
                .with(platformAdmin())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"Suspected fraud\" }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(newOrder(merchantId))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MERCHANT_NOT_ACTIVE"));
    }

    /** Reads stay open: a suspended merchant reconciling what happened is not a threat. */
    @Test
    void leavesReadsOpenToASuspendedMerchant() throws Exception {
        MerchantId merchantId = activated();
        changeMerchantStatus.suspend(merchantId, OPERATOR, "Suspected fraud");

        mockMvc.perform(get("/api/v1/orders").with(merchantAdmin(merchantId)))
            .andExpect(status().isOk());
    }

    /** And reinstating restores it. */
    @Test
    void restoresASuspendedMerchantOnReinstatement() throws Exception {
        MerchantId merchantId = activated();
        changeMerchantStatus.suspend(merchantId, OPERATOR, "Suspected fraud");

        mockMvc.perform(post("/api/v1/merchants/" + merchantId.value() + "/activate")
                .with(platformAdmin())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk());

        mockMvc.perform(newOrder(merchantId)).andExpect(status().isCreated());
    }

    // --- who may do what --------------------------------------------------------------------

    /** A merchant lifting its own suspension would make suspension advisory. */
    @Test
    void refusesAMerchantSuspendingItself() throws Exception {
        MerchantId merchantId = activated();

        mockMvc.perform(post("/api/v1/merchants/" + merchantId.value() + "/suspend")
                .with(merchantAdmin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"let me out\" }"))
            .andExpect(status().isForbidden());
    }

    @Test
    void refusesAMerchantReinstatingItself() throws Exception {
        MerchantId merchantId = activated();
        changeMerchantStatus.suspend(merchantId, OPERATOR, "Suspected fraud");

        mockMvc.perform(post("/api/v1/merchants/" + merchantId.value() + "/activate")
                .with(merchantAdmin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isForbidden());
    }

    /** THE ROLE IS READ NOW: an operational user cannot rename the company. */
    @Test
    void refusesAProfileEditToAMerchantUser() throws Exception {
        MerchantId merchantId = activated();

        mockMvc.perform(patch("/api/v1/merchants/" + merchantId.value())
                .with(merchantUser(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"businessName\": \"Renamed By A User\" }"))
            .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/merchants/" + merchantId.value())
                .with(merchantAdmin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"businessName\": \"Renamed By An Admin\" }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.businessName").value("Renamed By An Admin"));
    }

    // --- the audit record -------------------------------------------------------------------

    /** Every transition is on the record with its operator and reason. */
    @Test
    void recordsEveryTransitionWithItsOperatorAndReason() {
        MerchantId merchantId = activated();
        changeMerchantStatus.suspend(merchantId, OPERATOR, "Suspected fraud");

        List<String> rows = jdbc.sql("""
                select to_status || '/' || actor_type || '/' || coalesce(actor_id, '-')
                       || '/' || coalesce(reason, '-')
                  from merchant_status_history
                 where merchant_id = ? order by occurred_at, merchant_status_history_id
                """)
            .param(merchantId.value())
            .query(String.class)
            .list();

        assertThat(rows).containsExactly(
            "ACTIVE/PLATFORM/" + OPERATOR + "/Activated for test",
            "SUSPENDED/PLATFORM/" + OPERATOR + "/Suspected fraud"
        );
    }

    /**
     * ONE UNDECIDED SUBMISSION PER MERCHANT, enforced by a partial unique index rather than by a
     * pre-read -- otherwise a merchant can queue a hundred and an operator cannot tell which is
     * live.
     */
    @Test
    void refusesASecondOpenKycSubmission() throws Exception {
        MerchantId merchantId = register();

        mockMvc.perform(submitKyc(merchantId)).andExpect(status().isCreated());
        mockMvc.perform(submitKyc(merchantId)).andExpect(status().isConflict());
    }

    // --- helpers ------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submitKyc(
        MerchantId merchantId
    ) {
        return post("/api/v1/merchants/" + merchantId.value() + "/kyc-submissions")
            .with(merchantAdmin(merchantId))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "legalName": "Test Co Pvt Ltd", "registrationId": "U74999KA2020PTC000001" }
                """);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder newOrder(
        MerchantId merchantId
    ) {
        return post("/api/v1/orders")
            .with(merchantAdmin(merchantId))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"amountMinor\": 4000, \"currency\": \"INR\" }");
    }

    private MerchantId register() {
        return merchants.register(new RegisterMerchantCommand(
            "Governance Co", "gov-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();
    }

    private MerchantId activated() {
        MerchantId merchantId = register();
        changeMerchantStatus.activate(merchantId, OPERATOR, "Activated for test");

        return merchantId;
    }

    private static RequestPostProcessor merchantAdmin(MerchantId merchantId) {
        return caller("MERCHANT_ADMIN:" + merchantId.value());
    }

    private static RequestPostProcessor merchantUser(MerchantId merchantId) {
        return caller("MERCHANT_USER:" + merchantId.value());
    }

    /** Platform staff hold the role at a merchant, but the authority is platform-wide. */
    private static RequestPostProcessor platformAdmin() {
        return caller("PLATFORM_ADMIN:" + MerchantId.generate().value());
    }

    private static RequestPostProcessor caller(String scopedRole) {
        return jwt().jwt(builder -> builder.subject(OPERATOR).claim("roles", List.of(scopedRole)));
    }
}
