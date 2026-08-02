package com.paymesh.customer.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class CustomerControllerTest {

    private final MockMvc mockMvc;
    /** Platform staff, for fixtures that must activate a merchant. */
    private static final String PLATFORM_OPERATOR = "usr_00000000-0000-4000-8000-000000000001";

    private final RegisterMerchantService merchants;

    private final ChangeMerchantStatusService changeMerchantStatus;

    private String merchantId;
    private String otherMerchantId;

    @Autowired
    CustomerControllerTest(
        MockMvc mockMvc,
        RegisterMerchantService merchants,
        ChangeMerchantStatusService changeMerchantStatus
    ) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
        this.changeMerchantStatus = changeMerchantStatus;
    }

    @BeforeEach
    void registerTwoMerchants() {
        merchantId = registerMerchant();
        otherMerchantId = registerMerchant();
    }

    @Test
    void createsACustomerUnderTheCallersMerchant() throws Exception {
        mockMvc.perform(
                post("/api/v1/customers")
                    .with(callerFor(merchantId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "Buyer@Example.Test",
                          "merchantReference": "  order-42  ",
                          "name": "  Jamie Buyer  ",
                          "phone": " +91-555-0100 "
                        }
                        """)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(matchesPattern("cus_[0-9a-fA-F-]{36}")))
            .andExpect(jsonPath("$.merchantId").value(merchantId))
            .andExpect(jsonPath("$.email").value("buyer@example.test"))
            .andExpect(jsonPath("$.merchantReference").value("order-42"))
            .andExpect(jsonPath("$.name").value("Jamie Buyer"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            // The lookup hashes are an internal index key; publishing them would let a holder of
            // this response confirm a guessed email offline.
            .andExpect(jsonPath("$.emailHash").doesNotExist())
            .andExpect(jsonPath("$.phoneHash").doesNotExist());
    }

    @Test
    void readsBackACustomerItCreated() throws Exception {
        String customerId = createCustomer(merchantId, "readback@example.test", "ref-readback");

        mockMvc.perform(get("/api/v1/customers/{id}", customerId).with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(customerId))
            .andExpect(jsonPath("$.email").value("readback@example.test"));
    }

    // --- tenant isolation -----------------------------------------------------

    /**
     * The whole reason the customer API waited for authentication. The id is real and the caller is
     * authenticated, but the customer belongs to someone else, so it does not exist as far as this
     * caller is concerned.
     */
    @Test
    void hidesACustomerBelongingToAnotherMerchant() throws Exception {
        String customerId = createCustomer(merchantId, "victim@example.test", "ref-victim");

        mockMvc.perform(get("/api/v1/customers/{id}", customerId).with(callerFor(otherMerchantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    /** The tenant is never read from the request, so the same reference is free under each merchant. */
    @Test
    void allowsTheSameMerchantReferenceUnderADifferentMerchant() throws Exception {
        createCustomer(merchantId, "first@example.test", "shared-ref");
        createCustomer(otherMerchantId, "second@example.test", "shared-ref");
    }

    @Test
    void rejectsADuplicateMerchantReferenceWithinOneMerchant() throws Exception {
        createCustomer(merchantId, "first@example.test", "duplicate-ref");

        mockMvc.perform(
                post("/api/v1/customers")
                    .with(callerFor(merchantId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "second@example.test",
                          "merchantReference": "duplicate-ref"
                        }
                        """)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CUSTOMER_REFERENCE_ALREADY_EXISTS"));
    }

    // --- authentication and scope ---------------------------------------------

    @Test
    void rejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(
                post("/api/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nobody@example.test\"}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** Authenticated but holding no merchant role: there is no tenant to write under. */
    @Test
    void rejectsACallerWithNoMerchantScope() throws Exception {
        mockMvc.perform(
                post("/api/v1/customers")
                    .with(jwt().jwt(builder -> builder
                        .subject("usr_11111111-1111-4111-8111-111111111111")
                        .claim("roles", List.of())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nobody@example.test\"}")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    /**
     * A user serving several merchants is legitimate, but which one this write belongs to cannot be
     * guessed. Failing loudly beats writing the row under the wrong tenant.
     */
    @Test
    void rejectsACallerScopedToSeveralMerchants() throws Exception {
        mockMvc.perform(
                post("/api/v1/customers")
                    .with(jwt().jwt(builder -> builder
                        .subject("usr_11111111-1111-4111-8111-111111111111")
                        .claim("roles", List.of(
                            "MERCHANT_ADMIN:" + merchantId,
                            "MERCHANT_ADMIN:" + otherMerchantId
                        ))))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"ambiguous@example.test\"}")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    // --- validation -----------------------------------------------------------

    @Test
    void rejectsAMissingEmail() throws Exception {
        mockMvc.perform(
                post("/api/v1/customers")
                    .with(callerFor(merchantId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"merchantReference\":\"no-email\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void rejectsAMalformedCustomerId() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}", "not_a_customer_id").with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- helpers --------------------------------------------------------------

    private static RequestPostProcessor callerFor(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }

    private String createCustomer(String merchantId, String email, String reference)
        throws Exception {
        String body = mockMvc.perform(
                post("/api/v1/customers")
                    .with(callerFor(merchantId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "%s",
                          "merchantReference": "%s"
                        }
                        """.formatted(email, reference))
            )
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        return new ObjectMapper().readTree(body).get("id").asText();
    }

    private String registerMerchant() {
        // REGISTRATION PRODUCES PENDING_VERIFICATION, AND A PENDING MERCHANT CANNOT WRITE
        // (ADR-021). A fixture that goes on to create customers has to take the merchant through
        // the real activation path first, exactly as a platform operator would.
        MerchantId merchantId = merchants.register(
            new RegisterMerchantCommand(
                "Tenant Co",
                "tenant-" + UUID.randomUUID() + "@example.test",
                "IN",
                "INR"
            )
        ).merchantId();

        changeMerchantStatus.activate(merchantId, PLATFORM_OPERATOR, "Activated for test");

        return merchantId.value();
    }
}
