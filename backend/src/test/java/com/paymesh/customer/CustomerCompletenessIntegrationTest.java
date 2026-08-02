package com.paymesh.customer;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CreateCustomerCommand;
import com.paymesh.customer.application.CreateCustomerService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two customer gaps the Phase 1 audit found, closed.
 *
 * <h2>BLOCKED WAS STILL UNREACHABLE AFTER ADR-021</h2>
 *
 * ADR-021 added {@code Customer.block} and claimed "a customer can be blocked". The aggregate could
 * represent BLOCKED and no service or endpoint ever called the method, so the state stayed exactly
 * as unreachable as before -- the same defect ADR-021 exists to fix, one layer up. ADR-023 corrects
 * the record and closes it.
 *
 * <h2>payment_method_tokens HAD NEVER HELD A ROW</h2>
 *
 * The table has existed since V3, was tenant-fixed in V6, and had no writer -- SDD 10.3's endpoints
 * were never built, so "attach a payment method" attached the string "CARD" and no card was ever on
 * file.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CustomerCompletenessIntegrationTest {

    private static final String OPERATOR = "usr_00000000-0000-4000-8000-000000000001";
    private static final String FINGERPRINT = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private ChangeMerchantStatusService changeMerchantStatus;

    @Autowired
    private CreateCustomerService customers;

    @Autowired
    private JdbcClient jdbc;

    // --- the lifecycle that ADR-021 claimed and did not deliver ---------------------------------

    @Test
    void blocksAndUnblocksACustomer() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(post("/api/v1/customers/" + customerId + "/block")
                .with(admin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"Chargeback abuse\" }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("BLOCKED"));

        mockMvc.perform(post("/api/v1/customers/" + customerId + "/unblock").with(admin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** On the record with its actor and reason, like every other lifecycle in the platform. */
    @Test
    void recordsTheTransitionWithItsActor() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(post("/api/v1/customers/" + customerId + "/block")
                .with(admin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"Chargeback abuse\" }"))
            .andExpect(status().isOk());

        assertThat(jdbc.sql("""
                select to_status || '/' || actor_type || '/' || actor_id || '/' || reason
                  from customer_status_history where customer_id = ?
                """)
            .param(customerId)
            .query(String.class)
            .list())
            .containsExactly("BLOCKED/MERCHANT/" + OPERATOR + "/Chargeback abuse");
    }

    /** Refusing to sell to somebody is not a day-to-day operational act. */
    @Test
    void refusesBlockingToAMerchantUser() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(post("/api/v1/customers/" + customerId + "/block")
                .with(user(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"nope\" }"))
            .andExpect(status().isForbidden());
    }

    // --- payment methods --------------------------------------------------------------------------

    /** THE FIRST ROW THIS TABLE HAS EVER HELD. */
    @Test
    void attachesAndListsACardOnFile() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(attach(merchantId, customerId, FINGERPRINT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("pmt_")))
            .andExpect(jsonPath("$.brand").value("VISA"))
            .andExpect(jsonPath("$.lastFour").value("4242"));

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/payment-methods")
                .with(admin(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * THE PROVIDER TOKEN IS NEVER RETURNED. It is the one thing in the row that could charge the
     * card, and a response that echoes it turns every list into a way to harvest chargeable
     * handles. The response type has no field for it at all.
     */
    @Test
    void neverReturnsTheProviderToken() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        String created = mockMvc.perform(attach(merchantId, customerId, FINGERPRINT))
            .andReturn().getResponse().getContentAsString();

        String listed = mockMvc.perform(get("/api/v1/customers/" + customerId + "/payment-methods")
                .with(admin(merchantId)))
            .andReturn().getResponse().getContentAsString();

        assertThat(created).doesNotContain("tok_provider_secret");
        assertThat(listed).doesNotContain("tok_provider_secret");
        assertThat(listed).doesNotContain("providerToken");
    }

    /** The same card twice is two rows the merchant has to reason about. */
    @Test
    void refusesTheSameCardTwice() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(attach(merchantId, customerId, FINGERPRINT)).andExpect(status().isCreated());
        mockMvc.perform(attach(merchantId, customerId, FINGERPRINT)).andExpect(status().isConflict());
    }

    /**
     * RE-ATTACHING A CARD THE CUSTOMER REMOVED IS LEGITIMATE, and it needs a FRESH provider token.
     * <p>
     * The live-fingerprint index is partial, so the same CARD may come back. The V3 provider-token
     * constraint is not partial and never was -- a provider token is one stored instrument at the
     * provider, and re-attaching gets a new one in reality. The two are reported differently
     * because the fix is different.
     */
    @Test
    void allowsReattachingACardThatWasDetached() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        String tokenId = mockMvc.perform(attach(merchantId, customerId, FINGERPRINT))
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"id\":\"(pmt_[^\"]+)\".*", "$1");

        mockMvc.perform(delete("/api/v1/customers/" + customerId + "/payment-methods/" + tokenId)
                .with(admin(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.detachedAt").exists());

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/payment-methods")
                .with(admin(merchantId)))
            .andExpect(jsonPath("$.length()").value(0));

        // The same card -- same fingerprint -- with the fresh handle the provider would give.
        mockMvc.perform(attach(merchantId, customerId, FINGERPRINT, "tok_provider_reissued"))
            .andExpect(status().isCreated());
    }

    /** Re-using the provider token itself is refused, and says so distinctly. */
    @Test
    void refusesAReusedProviderTokenWithItsOwnMessage() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(attach(merchantId, customerId, FINGERPRINT)).andExpect(status().isCreated());

        mockMvc.perform(attach(merchantId, customerId, "b".repeat(64)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("provider token")));
    }

    /** Attaching is the first step of charging somebody a merchant has refused to sell to. */
    @Test
    void refusesToAttachACardToABlockedCustomer() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(post("/api/v1/customers/" + customerId + "/block")
                .with(admin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"Chargeback abuse\" }"))
            .andExpect(status().isOk());

        mockMvc.perform(attach(merchantId, customerId, FINGERPRINT))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void hidesAnotherMerchantsCardsBehindA404() throws Exception {
        MerchantId owner = activated();
        String customerId = customer(owner);
        mockMvc.perform(attach(owner, customerId, FINGERPRINT)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/payment-methods")
                .with(admin(activated())))
            .andExpect(status().isNotFound());
    }

    // --- update ------------------------------------------------------------------------------------

    /**
     * THE LOOKUP HASH MOVES WITH THE VALUE. The plaintext columns are display-only; the hashes carry
     * the indexes. Updating an email without recomputing its hash would leave the row findable by
     * its OLD address and invisible under its new one, with nothing reporting an error.
     */
    @Test
    void updatesContactDetailsAndTheirLookupHashes() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        String before = emailHash(customerId);

        mockMvc.perform(patch("/api/v1/customers/" + customerId)
                .with(admin(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"email\": \"moved@example.test\", \"name\": \"New Name\" }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("moved@example.test"))
            .andExpect(jsonPath("$.name").value("New Name"));

        assertThat(emailHash(customerId)).isNotEqualTo(before);
    }

    /** A null field means leave it alone, which is what makes this a PATCH. */
    @Test
    void leavesOmittedFieldsUntouched() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);

        mockMvc.perform(patch("/api/v1/customers/" + customerId)
                .with(admin(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"name\": \"Only The Name\" }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Only The Name"))
            .andExpect(jsonPath("$.email").exists());
    }

    /**
     * ATTACH IS IDEMPOTENCY-REGISTERED, so a retry replays rather than colliding.
     * <p>
     * Found in review: without it, a retried attach whose first attempt committed hits V3's
     * non-partial provider-token constraint and answers 409 -- the wrong answer to a network retry
     * of a request that worked, which is the exact reasoning capture is on that list for.
     */
    @Test
    void replaysARetriedAttachRatherThanColliding() throws Exception {
        MerchantId merchantId = activated();
        String customerId = customer(merchantId);
        String key = UUID.randomUUID().toString();

        String body = """
            {
              "provider": "SIMULATOR",
              "providerToken": "tok_retry",
              "fingerprint": "%s",
              "brand": "VISA",
              "lastFour": "4242"
            }
            """.formatted(FINGERPRINT);

        mockMvc.perform(post("/api/v1/customers/" + customerId + "/payment-methods")
                .with(admin(merchantId)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customers/" + customerId + "/payment-methods")
                .with(admin(merchantId)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("Idempotency-Replayed", "true"));
    }

    // --- helpers -------------------------------------------------------------------------------------

    private String emailHash(String customerId) {
        return jdbc.sql("select email_hash from customers where customer_id = ?")
            .param(customerId)
            .query(String.class)
            .single();
    }

    private MockHttpServletRequestBuilder attach(
        MerchantId merchantId, String customerId, String fingerprint
    ) {
        return attach(merchantId, customerId, fingerprint, "tok_provider_secret");
    }

    private MockHttpServletRequestBuilder attach(
        MerchantId merchantId, String customerId, String fingerprint, String providerToken
    ) {
        return post("/api/v1/customers/" + customerId + "/payment-methods")
            .with(admin(merchantId))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "provider": "SIMULATOR",
                  "providerToken": "%2$s",
                  "fingerprint": "%s",
                  "brand": "VISA",
                  "lastFour": "4242",
                  "expiryMonth": 12,
                  "expiryYear": 2030
                }
                """.formatted(fingerprint, providerToken));
    }

    private String customer(MerchantId merchantId) {
        return customers.create(new CreateCustomerCommand(
            merchantId, "CUST-" + UUID.randomUUID(), "buyer-" + UUID.randomUUID() + "@example.test",
            "A Buyer", null
        )).customerId().value();
    }

    private MerchantId activated() {
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Completeness Co", "comp-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();

        changeMerchantStatus.activate(merchantId, OPERATOR, "Activated for test");

        return merchantId;
    }

    private static RequestPostProcessor admin(MerchantId merchantId) {
        return caller("MERCHANT_ADMIN:" + merchantId.value());
    }

    private static RequestPostProcessor user(MerchantId merchantId) {
        return caller("MERCHANT_USER:" + merchantId.value());
    }

    private static RequestPostProcessor caller(String scopedRole) {
        return jwt().jwt(builder -> builder.subject(OPERATOR).claim("roles", List.of(scopedRole)));
    }
}
