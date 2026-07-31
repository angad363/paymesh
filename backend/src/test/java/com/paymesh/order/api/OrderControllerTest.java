package com.paymesh.order.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CreateCustomerCommand;
import com.paymesh.customer.application.CreateCustomerService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deliberately NOT {@code @Transactional}: POST /api/v1/orders runs behind the idempotency filter,
 * whose record has to genuinely commit before the handler runs. A test transaction would roll that
 * back and make every replay assertion here meaningless.
 * <p>
 * Each test therefore registers its own merchants, so rows surviving between tests cannot make one
 * test's assertions depend on another's.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OrderControllerTest {

    private final MockMvc mockMvc;
    private final RegisterMerchantService merchants;
    private final CreateCustomerService customers;

    private String merchantId;
    private String otherMerchantId;

    @Autowired
    OrderControllerTest(
        MockMvc mockMvc,
        RegisterMerchantService merchants,
        CreateCustomerService customers
    ) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
        this.customers = customers;
    }

    @BeforeEach
    void registerTwoMerchants() {
        merchantId = registerMerchant();
        otherMerchantId = registerMerchant();
    }

    // --- create ---------------------------------------------------------------

    @Test
    void createsAnOrderUnderTheCallersMerchant() throws Exception {
        mockMvc.perform(newOrder(merchantId, key(), """
                {
                  "merchantOrderReference": "  ORDER-7788  ",
                  "amountMinor": 1999,
                  "currency": "inr",
                  "description": "Two masala chai",
                  "metadata": {"channel": "web"}
                }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(matchesPattern("ord_[0-9a-fA-F-]{36}")))
            .andExpect(jsonPath("$.merchantId").value(merchantId))
            .andExpect(jsonPath("$.merchantOrderReference").value("ORDER-7788"))
            .andExpect(jsonPath("$.amountMinor").value(1999))
            .andExpect(jsonPath("$.currency").value("INR"))
            .andExpect(jsonPath("$.amountPaidMinor").value(0))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.metadata.channel").value("web"))
            .andExpect(jsonPath("$.cancelledAt").doesNotExist());
    }

    /** Money is an integer count of minor units; a fractional amount is not a smaller order. */
    @Test
    void rejectsAZeroAmount() throws Exception {
        mockMvc.perform(newOrder(merchantId, key(), """
                {"amountMinor": 0, "currency": "INR"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());
    }

    @Test
    void rejectsAMissingAmount() throws Exception {
        mockMvc.perform(newOrder(merchantId, key(), """
                {"currency": "INR"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsACurrencyThatIsNotThreeLetters() throws Exception {
        mockMvc.perform(newOrder(merchantId, key(), """
                {"amountMinor": 1999, "currency": "RUPEE"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // --- the merchant's own reference ------------------------------------------

    @Test
    void rejectsADuplicateMerchantOrderReferenceWithinOneMerchant() throws Exception {
        createOrder(merchantId, "ORDER-7788");

        mockMvc.perform(newOrder(merchantId, key(), """
                {"merchantOrderReference": "ORDER-7788", "amountMinor": 500, "currency": "INR"}
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORDER_REFERENCE_ALREADY_EXISTS"));
    }

    /** The tenant is never read from the request, so the same reference is free under each merchant. */
    @Test
    void allowsTheSameMerchantOrderReferenceUnderADifferentMerchant() throws Exception {
        createOrder(merchantId, "ORDER-7788");
        createOrder(otherMerchantId, "ORDER-7788");
    }

    // --- the customer link ------------------------------------------------------

    @Test
    void linksAnOrderToACustomerOfTheSameMerchant() throws Exception {
        String customerId = createCustomer(merchantId);

        mockMvc.perform(newOrder(merchantId, key(), """
                {"customerId": "%s", "amountMinor": 1999, "currency": "INR"}
                """.formatted(customerId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.customerId").value(customerId));
    }

    /**
     * THE CROSS-MODULE ISOLATION CASE. The customer is real; it belongs to the other merchant. The
     * answer must be identical to the one for a customer that never existed, or this endpoint
     * becomes an oracle for another tenant's customer ids.
     */
    @Test
    void rejectsACustomerBelongingToAnotherMerchantWithoutRevealingThatItExists() throws Exception {
        String customerOfOther = createCustomer(otherMerchantId);

        String forStranger = mockMvc.perform(newOrder(merchantId, key(), """
                {"customerId": "%s", "amountMinor": 1999, "currency": "INR"}
                """.formatted(customerOfOther)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String unknownCustomerId = "cus_" + UUID.randomUUID();

        String forUnknown = mockMvc.perform(newOrder(merchantId, key(), """
                {"customerId": "%s", "amountMinor": 1999, "currency": "INR"}
                """.formatted(unknownCustomerId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        org.assertj.core.api.Assertions
            .assertThat(forStranger.replace(customerOfOther, "X"))
            .as("the two rejections must be indistinguishable once the echoed id is masked")
            .isEqualTo(forUnknown.replace(unknownCustomerId, "X"));
    }

    // --- read -------------------------------------------------------------------

    @Test
    void readsBackAnOrderItCreated() throws Exception {
        String orderId = createOrder(merchantId, null);

        mockMvc.perform(get("/api/v1/orders/{id}", orderId).with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /**
     * The id is real and the caller is authenticated, but the order belongs to someone else, so it
     * does not exist as far as this caller is concerned. 404, never 403: a 403 confirms the id.
     */
    @Test
    void hidesAnOrderBelongingToAnotherMerchant() throws Exception {
        String orderId = createOrder(merchantId, null);

        mockMvc.perform(get("/api/v1/orders/{id}", orderId).with(callerFor(otherMerchantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void rejectsAMalformedOrderId() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", "not_an_order_id").with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- list -------------------------------------------------------------------

    @Test
    void listsTheCallersOrdersInAPaginationEnvelope() throws Exception {
        createOrder(merchantId, null);
        createOrder(merchantId, null);

        mockMvc.perform(get("/api/v1/orders").with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.pagination.limit").value(20))
            .andExpect(jsonPath("$.pagination.hasMore").value(false))
            .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist());
    }

    @Test
    void excludesAnotherMerchantsOrdersFromTheList() throws Exception {
        createOrder(merchantId, null);

        mockMvc.perform(get("/api/v1/orders").with(callerFor(otherMerchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0))
            .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }

    @Test
    void pagesWithAnOpaqueCursor() throws Exception {
        createOrder(merchantId, null);
        createOrder(merchantId, null);

        MvcResult first = mockMvc.perform(
                get("/api/v1/orders").param("limit", "1").with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.pagination.hasMore").value(true))
            .andReturn();

        String cursor = json(first).get("pagination").get("nextCursor").asText();
        String firstId = json(first).get("data").get(0).get("id").asText();

        mockMvc.perform(get("/api/v1/orders")
                .param("limit", "1")
                .param("cursor", cursor)
                .with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(org.hamcrest.Matchers.not(firstId)))
            .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }

    @Test
    void filtersTheListByStatus() throws Exception {
        createOrder(merchantId, null);
        String cancelled = createOrder(merchantId, null);
        cancel(merchantId, cancelled, key()).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/orders").param("status", "CANCELLED").with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(cancelled));
    }

    @Test
    void capsAnOverlargeLimit() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("limit", "5000").with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pagination.limit").value(100));
    }

    @Test
    void rejectsALimitBelowOne() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("limit", "0").with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAnUnknownStatusFilter() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("status", "NOPE").with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAMalformedCursor() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("cursor", "!!!").with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- cancel -------------------------------------------------------------------

    @Test
    void cancelsAPendingOrder() throws Exception {
        String orderId = createOrder(merchantId, null);

        cancel(merchantId, orderId, key())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationReason").value("out of stock"))
            .andExpect(jsonPath("$.cancelledAt").exists());
    }

    /**
     * The two dedup rules, side by side. A SECOND cancel on a FRESH key is a genuinely new request
     * and must meet the state machine: 409. The same request replayed on the SAME key never reaches
     * the state machine at all and gets the original 200 back.
     */
    @Test
    void refusesASecondCancellationOnAFreshKeyButReplaysItOnTheSameKey() throws Exception {
        String orderId = createOrder(merchantId, null);
        String firstKey = key();

        cancel(merchantId, orderId, firstKey).andExpect(status().isOk());

        cancel(merchantId, orderId, key())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));

        cancel(merchantId, orderId, firstKey)
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void refusesToCancelAnotherMerchantsOrder() throws Exception {
        String orderId = createOrder(merchantId, null);

        cancel(otherMerchantId, orderId, key())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void cancelsWithoutABody() throws Exception {
        String orderId = createOrder(merchantId, null);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                .with(callerFor(merchantId))
                .header("Idempotency-Key", key()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationReason").doesNotExist());
    }

    // --- idempotency ---------------------------------------------------------------

    @Test
    void rejectsACreateWithNoIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": 1999, \"currency\": \"INR\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void rejectsACancelWithNoIdempotencyKey() throws Exception {
        String orderId = createOrder(merchantId, null);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /** A network retry must cost nothing: the same key and body returns the original order. */
    @Test
    void replaysTheOriginalResponseForARetriedCreate() throws Exception {
        String idempotencyKey = key();
        String body = """
            {"amountMinor": 1999, "currency": "INR"}
            """;

        MvcResult first = mockMvc.perform(newOrder(merchantId, idempotencyKey, body))
            .andExpect(status().isCreated())
            .andReturn();

        MvcResult retry = mockMvc.perform(newOrder(merchantId, idempotencyKey, body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andReturn();

        org.assertj.core.api.Assertions
            .assertThat(retry.getResponse().getContentAsString())
            .as("a retry must not create a second order")
            .isEqualTo(first.getResponse().getContentAsString());

        mockMvc.perform(get("/api/v1/orders").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void rejectsTheSameIdempotencyKeyCarryingADifferentBody() throws Exception {
        String idempotencyKey = key();

        mockMvc.perform(newOrder(merchantId, idempotencyKey, """
                {"amountMinor": 1999, "currency": "INR"}
                """))
            .andExpect(status().isCreated());

        mockMvc.perform(newOrder(merchantId, idempotencyKey, """
                {"amountMinor": 9999, "currency": "INR"}
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    /** The key is scoped to the merchant, so one merchant's key cannot collide with another's. */
    @Test
    void scopesTheIdempotencyKeyToTheMerchant() throws Exception {
        String sharedKey = key();
        String body = """
            {"amountMinor": 1999, "currency": "INR"}
            """;

        mockMvc.perform(newOrder(merchantId, sharedKey, body)).andExpect(status().isCreated());
        mockMvc.perform(newOrder(otherMerchantId, sharedKey, body))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotency-Replayed"));
    }

    // --- authentication and scope ---------------------------------------------------

    @Test
    void rejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": 1999, \"currency\": \"INR\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsAnUnauthenticatedRead() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
            .andExpect(status().isUnauthorized());
    }

    /** Authenticated but holding no merchant role: there is no tenant to write under. */
    @Test
    void rejectsACallerWithNoMerchantScope() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(builder -> builder
                    .subject("usr_11111111-1111-4111-8111-111111111111")
                    .claim("roles", List.of())))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": 1999, \"currency\": \"INR\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    @Test
    void rejectsAReadFromACallerScopedToSeveralMerchants() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                .with(jwt().jwt(builder -> builder
                    .subject("usr_11111111-1111-4111-8111-111111111111")
                    .claim("roles", List.of(
                        "MERCHANT_ADMIN:" + merchantId,
                        "MERCHANT_ADMIN:" + otherMerchantId
                    )))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    // --- helpers ---------------------------------------------------------------------

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static RequestPostProcessor callerFor(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder newOrder(
        String merchantId,
        String idempotencyKey,
        String body
    ) {
        return post("/api/v1/orders")
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private org.springframework.test.web.servlet.ResultActions cancel(
        String merchantId,
        String orderId,
        String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\": \"out of stock\"}"));
    }

    private String createOrder(String merchantId, String merchantOrderReference) throws Exception {
        String body = merchantOrderReference == null
            ? """
            {"amountMinor": 1999, "currency": "INR"}
            """
            : """
            {"merchantOrderReference": "%s", "amountMinor": 1999, "currency": "INR"}
            """.formatted(merchantOrderReference);

        MvcResult result = mockMvc.perform(newOrder(merchantId, key(), body))
            .andExpect(status().isCreated())
            .andReturn();

        return json(result).get("id").asText();
    }

    private String createCustomer(String merchantId) {
        return customers.create(new CreateCustomerCommand(
            MerchantId.from(merchantId),
            null,
            UUID.randomUUID() + "@buyer.test",
            "Asha Rao",
            null
        )).customerId().value();
    }

    private String registerMerchant() {
        return merchants.register(new RegisterMerchantCommand(
            "Tenant Co", "tenant-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId().value();
    }

    private static tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }
}
