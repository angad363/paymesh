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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SERVER-TO-SERVER AUTHENTICATION, end to end against a real PostgreSQL.
 * <p>
 * The headline is that a key can create an order without any human token anywhere -- which is what
 * SDD §10.3 and §11.3 assume and what the platform could not do. The rest is proving a key is not a
 * way around anything: it obeys the merchant status gate, it obeys the role model, and it dies when
 * revoked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiCredentialIntegrationTest {

    private static final String OPERATOR = "usr_00000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private ChangeMerchantStatusService changeMerchantStatus;

    // --- the point ------------------------------------------------------------------------------

    /**
     * A MACHINE CREATES AN ORDER WITH NO HUMAN CREDENTIAL ANYWHERE.
     * <p>
     * <b>Sabotage that must turn this red:</b> register the filter as an ordinary
     * {@code FilterRegistrationBean} instead of inserting it into the security chain. It then runs
     * after {@code .anyRequest().authenticated()} and every ApiKey request is 401 before the filter
     * ever sees the header.
     */
    @Test
    void createsAnOrderAuthenticatedOnlyByAnApiKey() throws Exception {
        MerchantId merchantId = activated();
        String key = issueKey(merchantId, "MERCHANT_USER");

        mockMvc.perform(post("/api/v1/orders")
                .header("Authorization", "ApiKey " + key)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"amountMinor\": 4000, \"currency\": \"INR\" }"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.merchantId").value(merchantId.value()));
    }

    /** The secret appears once, in the create response, and never again. */
    @Test
    void returnsTheSecretExactlyOnce() throws Exception {
        MerchantId merchantId = activated();

        String body = mockMvc.perform(newCredential(merchantId, "MERCHANT_USER"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.secret").exists())
            .andReturn().getResponse().getContentAsString();

        String credentialId = body.replaceAll(".*\"id\":\"(apc_[^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/merchants/" + merchantId.value() + "/api-credentials")
                .with(merchantAdmin(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(credentialId))
            .andExpect(jsonPath("$[0].secret").doesNotExist())
            .andExpect(jsonPath("$[0].secretHash").doesNotExist());
    }

    // --- a key is not a way around anything -----------------------------------------------------

    /** THE MERCHANT STATUS GATE APPLIES TO MACHINES TOO (ADR-021). */
    @Test
    void refusesAKeyBelongingToASuspendedMerchant() throws Exception {
        MerchantId merchantId = activated();
        String key = issueKey(merchantId, "MERCHANT_USER");

        mockMvc.perform(orderWithKey(key)).andExpect(status().isCreated());

        changeMerchantStatus.suspend(merchantId, OPERATOR, "Suspected fraud");

        mockMvc.perform(orderWithKey(key))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MERCHANT_NOT_ACTIVE"));
    }

    /** THE ROLE MODEL APPLIES TO MACHINES TOO: a USER key cannot mint a key. */
    @Test
    void refusesCredentialCreationToAMerchantUserKey() throws Exception {
        MerchantId merchantId = activated();
        String key = issueKey(merchantId, "MERCHANT_USER");

        mockMvc.perform(post("/api/v1/merchants/" + merchantId.value() + "/api-credentials")
                .header("Authorization", "ApiKey " + key)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"role\": \"MERCHANT_ADMIN\", \"label\": \"escalation\" }"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
    }

    /** And no key may ever be PLATFORM_ADMIN, whoever asks. */
    @Test
    void refusesAPlatformAdminKey() throws Exception {
        MerchantId merchantId = activated();

        mockMvc.perform(newCredential(merchantId, "PLATFORM_ADMIN"))
            .andExpect(status().isBadRequest());
    }

    /** A key is scoped to one merchant and cannot reach another's data. */
    @Test
    void cannotReachAnotherMerchant() throws Exception {
        MerchantId owner = activated();
        MerchantId stranger = activated();
        String key = issueKey(owner, "MERCHANT_ADMIN");

        mockMvc.perform(get("/api/v1/merchants/" + stranger.value())
                .header("Authorization", "ApiKey " + key))
            .andExpect(status().isNotFound());
    }

    // --- revocation -----------------------------------------------------------------------------

    @Test
    void stopsWorkingTheMomentItIsRevoked() throws Exception {
        MerchantId merchantId = activated();

        String body = mockMvc.perform(newCredential(merchantId, "MERCHANT_USER"))
            .andReturn().getResponse().getContentAsString();

        String key = body.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        String credentialId = body.replaceAll(".*\"id\":\"(apc_[^\"]+)\".*", "$1");

        mockMvc.perform(orderWithKey(key)).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/merchants/" + merchantId.value()
                + "/api-credentials/" + credentialId)
                .with(merchantAdmin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revokedAt").exists());

        mockMvc.perform(orderWithKey(key))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("API_KEY_INVALID"));
    }

    // --- bad keys all look the same -------------------------------------------------------------

    /**
     * UNKNOWN, MALFORMED AND WRONG-SECRET ARE ONE ANSWER. Distinguishing them would confirm which
     * prefixes exist, and a revoked key answering differently tells an attacker they once had
     * something real.
     */
    @Test
    void answersEveryBadKeyIdentically() throws Exception {
        MerchantId merchantId = activated();
        String good = issueKey(merchantId, "MERCHANT_USER");
        String prefix = good.substring(0, good.indexOf('.'));

        List<String> bad = List.of(
            "ak_nosuchprefix.somesecret",
            prefix + ".wrong-secret",
            "no-separator-at-all",
            "ak_only_a_prefix.",
            ".only-a-secret"
        );

        for (String key : bad) {
            mockMvc.perform(orderWithKey(key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_INVALID"));
        }
    }

    /** A bearer token still works; adding a scheme did not remove one. */
    @Test
    void leavesBearerAuthenticationWorking() throws Exception {
        MerchantId merchantId = activated();

        mockMvc.perform(post("/api/v1/orders")
                .with(merchantAdmin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"amountMinor\": 4000, \"currency\": \"INR\" }"))
            .andExpect(status().isCreated());
    }

    @Test
    void stillRefusesARequestWithNoCredentialAtAll() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"amountMinor\": 4000, \"currency\": \"INR\" }"))
            .andExpect(status().isUnauthorized());
    }

    /** Records use so an operator can find keys nobody has rotated. */
    @Test
    void recordsWhenAKeyWasLastUsed() throws Exception {
        MerchantId merchantId = activated();
        String key = issueKey(merchantId, "MERCHANT_USER");

        mockMvc.perform(orderWithKey(key)).andExpect(status().isCreated());

        String listed = mockMvc.perform(
                get("/api/v1/merchants/" + merchantId.value() + "/api-credentials")
                    .with(merchantAdmin(merchantId)))
            .andReturn().getResponse().getContentAsString();

        assertThat(listed).contains("\"lastUsedAt\":");
        assertThat(listed).doesNotContain("\"lastUsedAt\":null");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder orderWithKey(String key) {
        return post("/api/v1/orders")
            .header("Authorization", "ApiKey " + key)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"amountMinor\": 4000, \"currency\": \"INR\" }");
    }

    private MockHttpServletRequestBuilder newCredential(MerchantId merchantId, String role) {
        return post("/api/v1/merchants/" + merchantId.value() + "/api-credentials")
            .with(merchantAdmin(merchantId))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"role\": \"" + role + "\", \"label\": \"integration\" }");
    }

    private String issueKey(MerchantId merchantId, String role) throws Exception {
        return mockMvc.perform(newCredential(merchantId, role))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
    }

    private MerchantId activated() {
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Keys Co", "keys-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();

        changeMerchantStatus.activate(merchantId, OPERATOR, "Activated for test");

        return merchantId;
    }

    private static RequestPostProcessor merchantAdmin(MerchantId merchantId) {
        return jwt().jwt(builder -> builder
            .subject(OPERATOR)
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId.value())));
    }
}
