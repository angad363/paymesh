package com.paymesh.payment.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payment intent HTTP contract, end to end through the real filter chain and a real database.
 * <p>
 * Deliberately NOT {@code @Transactional}: both writes run behind the idempotency filter, whose
 * record has to genuinely commit before the handler runs. A test transaction would roll that back
 * and make every replay assertion here meaningless.
 * <p>
 * Each test registers its own merchants and its own order, so rows surviving between tests cannot
 * make one test's assertions depend on another's. An order is a precondition for every intent: the
 * amount rule compares against it and the composite foreign key requires it to exist.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PaymentIntentControllerTest {

    private static final long ORDER_AMOUNT_MINOR = 1999;

    private final MockMvc mockMvc;
    private final RegisterMerchantService merchants;

    private String merchantId;
    private String otherMerchantId;

    @Autowired
    PaymentIntentControllerTest(MockMvc mockMvc, RegisterMerchantService merchants) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
    }

    @BeforeEach
    void registerTwoMerchants() {
        merchantId = registerMerchant();
        otherMerchantId = registerMerchant();
    }

    // --- create -----------------------------------------------------------------

    @Test
    void createsAPaymentIntentUnderTheCallersMerchant() throws Exception {
        String orderId = createOrder(merchantId);

        mockMvc.perform(newIntent(merchantId, key(), """
                {
                  "orderId": "%s",
                  "amountMinor": 1999,
                  "currency": "inr",
                  "description": "Two masala chai",
                  "metadata": {"channel": "web"}
                }
                """.formatted(orderId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(matchesPattern("pi_[0-9a-fA-F-]{36}")))
            .andExpect(jsonPath("$.merchantId").value(merchantId))
            .andExpect(jsonPath("$.orderId").value(orderId))
            .andExpect(jsonPath("$.amountMinor").value(1999))
            // "inr" goes in and "INR" comes back: a lowercase code is normalized, not refused.
            .andExpect(jsonPath("$.currency").value("INR"))
            .andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT_METHOD"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(0))
            // The body named no capture method, so the aggregate chose the default.
            .andExpect(jsonPath("$.captureMethod").value("AUTOMATIC"))
            .andExpect(jsonPath("$.metadata.channel").value("web"))
            .andExpect(jsonPath("$.cancelledAt").doesNotExist());
    }

    @Test
    void createsAPaymentIntentHeldForManualCapture() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1999, "currency": "INR", "captureMethod": "manual"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.captureMethod").value("MANUAL"));
    }

    /**
     * SDD 12.3 puts a clientSecret and an allowedPaymentMethods list on this response and this design
     * issues neither (spec 2.6). Asserted as an ABSENCE rather than left untested: a credential
     * nothing in PayMesh can verify looks like an authorization boundary and is not one, so it
     * appearing later must break a test rather than pass review as an addition.
     */
    @Test
    void issuesNoClientSecretAndNoAllowedPaymentMethods() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1999, "currency": "INR"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.clientSecret").doesNotExist())
            .andExpect(jsonPath("$.allowedPaymentMethods").doesNotExist());
    }

    // --- the order link -----------------------------------------------------------

    /**
     * THE ENUMERATION CASE, all three causes at once. Another merchant's order, an order that never
     * existed, and a cancelled order must produce byte-identical answers once the echoed id is
     * masked. Splitting them would turn this endpoint into an oracle for another tenant's order ids,
     * which is exactly what ADR-008 stopped the customer link from becoming.
     */
    @Test
    void refusesAnUnpayableOrderIdenticallyWhoeverOwnsItAndWhateverStateItIsIn() throws Exception {
        String orderOfOther = createOrder(otherMerchantId);
        String unknownOrder = "ord_" + UUID.randomUUID();
        String cancelledOrder = createOrder(merchantId);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", cancelledOrder)
                .with(callerFor(merchantId))
                .header("Idempotency-Key", key()))
            .andExpect(status().isOk());

        String forStranger = notPayable(orderOfOther).replace(orderOfOther, "X");
        String forUnknown = notPayable(unknownOrder).replace(unknownOrder, "X");
        String forCancelled = notPayable(cancelledOrder).replace(cancelledOrder, "X");

        assertThat(forStranger)
            .as("another tenant's order must look exactly like one that does not exist")
            .isEqualTo(forUnknown);
        assertThat(forCancelled)
            .as("and exactly like one of the caller's own that cannot be paid")
            .isEqualTo(forUnknown);
    }

    // --- the amount rule ------------------------------------------------------------

    /**
     * An intent collects its order's exact obligation. This is the v1 narrowing that makes
     * overpayment structurally impossible rather than merely CHECK-constrained, so both directions
     * are refused.
     */
    @Test
    void refusesAnAmountThatIsNotTheOrders() throws Exception {
        String orderId = createOrder(merchantId);

        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 2999, "currency": "INR"}
                """.formatted(orderId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));

        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1998, "currency": "INR"}
                """.formatted(orderId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
    }

    @Test
    void refusesACurrencyThatIsNotTheOrders() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1999, "currency": "USD"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
    }

    // --- one live intent per order -----------------------------------------------------

    /**
     * A FRESH idempotency key on purpose: with the first request's key the idempotency layer would
     * answer from its stored response and the state machine would never be consulted, so this would
     * prove nothing about the one-live-intent rule.
     */
    @Test
    void refusesASecondLiveIntentForOneOrder() throws Exception {
        String orderId = createOrder(merchantId);
        createIntent(merchantId, orderId);

        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1999, "currency": "INR"}
                """.formatted(orderId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORDER_HAS_ACTIVE_PAYMENT_INTENT"));
    }

    // --- read ---------------------------------------------------------------------------

    @Test
    void readsBackAnIntentItCreated() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(intentId))
            .andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT_METHOD"));
    }

    /**
     * The id is real and the caller is authenticated, but the intent belongs to someone else, so as
     * far as this caller is concerned it does not exist. Compared byte for byte against a genuinely
     * unknown id: a 403, or a differently worded 404, would confirm which ids exist under another
     * tenant.
     */
    @Test
    void hidesAnIntentBelongingToAnotherMerchantIndistinguishablyFromOneThatDoesNotExist()
        throws Exception {
        String intentOfOther = createIntent(merchantId, createOrder(merchantId));
        String unknownIntent = "pi_" + UUID.randomUUID();

        String forStranger = notFound(otherMerchantId, intentOfOther).replace(intentOfOther, "X");
        String forUnknown = notFound(otherMerchantId, unknownIntent).replace(unknownIntent, "X");

        assertThat(forStranger).isEqualTo(forUnknown);
    }

    @Test
    void rejectsAMalformedPaymentIntentId() throws Exception {
        mockMvc.perform(get("/api/v1/payment-intents/{id}", "not_an_intent_id")
                .with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- validation --------------------------------------------------------------------

    @Test
    void rejectsAMissingOrderId() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"amountMinor": 1999, "currency": "INR"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.orderId").exists());
    }

    @Test
    void rejectsAMissingAmount() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "currency": "INR"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());
    }

    /** Money is an integer count of minor units; collecting nothing is not a smaller collection. */
    @Test
    void rejectsAZeroAmount() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 0, "currency": "INR"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());
    }

    @Test
    void rejectsANegativeAmount() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": -1999, "currency": "INR"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());
    }

    @Test
    void rejectsACurrencyThatIsNotThreeLetters() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1999, "currency": "RUPEE"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.currency").exists());
    }

    @Test
    void rejectsAnUnknownCaptureMethod() throws Exception {
        mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1999, "currency": "INR", "captureMethod": "LATER"}
                """.formatted(createOrder(merchantId))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.captureMethod").exists());
    }

    // --- list ----------------------------------------------------------------------------

    @Test
    void listsTheCallersIntentsInAPaginationEnvelope() throws Exception {
        createIntent(merchantId, createOrder(merchantId));
        createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(get("/api/v1/payment-intents").with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.pagination.limit").value(20))
            .andExpect(jsonPath("$.pagination.hasMore").value(false))
            .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist());
    }

    @Test
    void excludesAnotherMerchantsIntentsFromTheList() throws Exception {
        createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(get("/api/v1/payment-intents").with(callerFor(otherMerchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void filtersTheListByStatus() throws Exception {
        createIntent(merchantId, createOrder(merchantId));
        String cancelled = createIntent(merchantId, createOrder(merchantId));
        cancel(merchantId, cancelled, key()).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/payment-intents")
                .param("status", "CANCELLED")
                .with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(cancelled));
    }

    @Test
    void filtersTheListByOrder() throws Exception {
        String orderId = createOrder(merchantId);
        String intentId = createIntent(merchantId, orderId);
        createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(get("/api/v1/payment-intents")
                .param("orderId", orderId)
                .with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(intentId));
    }

    /** Cannot be honoured, and silently turning it into 20 would hand back a page nobody asked for. */
    @Test
    void rejectsALimitBelowOne() throws Exception {
        mockMvc.perform(get("/api/v1/payment-intents")
                .param("limit", "0")
                .with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAMalformedCursor() throws Exception {
        mockMvc.perform(get("/api/v1/payment-intents")
                .param("cursor", "!!!")
                .with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- attach ---------------------------------------------------------------------------

    @Test
    void attachesAPaymentMethodAndAwaitsConfirmation() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        attach(merchantId, intentId, key(), "CARD")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(intentId))
            .andExpect(jsonPath("$.status").value("REQUIRES_CONFIRMATION"))
            .andExpect(jsonPath("$.paymentMethodType").value("CARD"));
    }

    /** A lowercase type is normalized, not refused -- the same courtesy currency gets. */
    @Test
    void normalizesTheAttachedMethodType() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        attach(merchantId, intentId, key(), "net_banking")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethodType").value("NET_BANKING"));
    }

    /**
     * THE ONE INTENT AWAITING A METHOD HAS NO METHOD. Asserted as an absence so that a future change
     * defaulting it to CARD breaks a test rather than passing review.
     */
    @Test
    void reportsNoPaymentMethodBeforeOneIsAttached() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethodType").doesNotExist());
    }

    /**
     * The two dedup rules again. A second attach on a FRESH key is a genuinely new request and meets
     * the state machine: 409. The same request replayed on the SAME key never reaches it.
     */
    @Test
    void refusesASecondAttachOnAFreshKeyButReplaysItOnTheSameKey() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        String firstKey = key();

        attach(merchantId, intentId, firstKey, "CARD").andExpect(status().isOk());

        attach(merchantId, intentId, key(), "CARD")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_NOT_ATTACHABLE"));

        attach(merchantId, intentId, firstKey, "CARD")
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.status").value("REQUIRES_CONFIRMATION"));
    }

    @Test
    void rejectsAnUnknownPaymentMethodType() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        attach(merchantId, intentId, key(), "CRYPTO")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.paymentMethodType").exists());
    }

    @Test
    void rejectsAnAttachWithNoIdempotencyKey() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(post("/api/v1/payment-intents/{id}/payment-method", intentId)
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodType\": \"CARD\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void refusesToAttachToAnotherMerchantsIntent() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        attach(otherMerchantId, intentId, key(), "CARD")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
    }

    // --- confirm --------------------------------------------------------------------------

    /**
     * 202, NOT 200, even though nothing asynchronous is invoked. The request is accepted and the
     * outcome is undecided until a provider callback resolves it; 200 would claim the work is done.
     */
    @Test
    void confirmsAnAttachedIntentWithAnAccepted() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        attach(merchantId, intentId, key(), "UPI").andExpect(status().isOk());

        confirm(merchantId, intentId, key())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(intentId))
            .andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.paymentMethodType").value("UPI"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(0));
    }

    /** Attach is a prerequisite, not a convention. */
    @Test
    void refusesToConfirmAnIntentWithNoPaymentMethod() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        confirm(merchantId, intentId, key())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CONFIRMABLE"));
    }

    @Test
    void refusesASecondConfirmOnAFreshKeyButReplaysItOnTheSameKey() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        attach(merchantId, intentId, key(), "CARD").andExpect(status().isOk());
        String firstKey = key();

        confirm(merchantId, intentId, firstKey).andExpect(status().isAccepted());

        confirm(merchantId, intentId, key())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CONFIRMABLE"));

        confirm(merchantId, intentId, firstKey)
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    /**
     * ADR-013, OVER HTTP. The merchant cancels the order after the intent exists -- which nothing
     * stops, because Order does not know Payment exists -- and the confirm that would have collected
     * for it is refused 422. This is the single most important test in this class.
     */
    @Test
    void refusesToConfirmAgainstAnOrderCancelledAfterTheIntentWasCreated() throws Exception {
        String orderId = createOrder(merchantId);
        String intentId = createIntent(merchantId, orderId);
        attach(merchantId, intentId, key(), "CARD").andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                .with(callerFor(merchantId))
                .header("Idempotency-Key", key()))
            .andExpect(status().isOk());

        confirm(merchantId, intentId, key())
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));

        mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).with(callerFor(merchantId)))
            .andExpect(jsonPath("$.status").value("REQUIRES_CONFIRMATION"));
    }

    /** Malformed at the boundary, so it never reaches the redactor that would have dropped it. */
    @Test
    void rejectsAReturnUrlThatIsNotAnAbsoluteHttpUrl() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        attach(merchantId, intentId, key(), "CARD").andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payment-intents/{id}/confirm", intentId)
                .with(callerFor(merchantId))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"returnUrl\": \"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.returnUrl").exists());
    }

    @Test
    void rejectsAConfirmWithNoIdempotencyKey() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(post("/api/v1/payment-intents/{id}/confirm", intentId)
                .with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void refusesToConfirmAnotherMerchantsIntent() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        attach(merchantId, intentId, key(), "CARD").andExpect(status().isOk());

        confirm(otherMerchantId, intentId, key())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
    }

    // --- cancel -----------------------------------------------------------------------------

    /** The slot-release route out of REQUIRES_CONFIRMATION (ADR-011). */
    @Test
    void cancelsAnIntentAwaitingConfirmation() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        attach(merchantId, intentId, key(), "WALLET").andExpect(status().isOk());

        cancel(merchantId, intentId, key())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            // The method it died holding is kept, not cleared.
            .andExpect(jsonPath("$.paymentMethodType").value("WALLET"));
    }

    /**
     * PROCESSING IS DELIBERATELY UNCANCELLABLE (ADR-011 section 5). An in-flight attempt may already
     * have succeeded at the provider, so a local cancel could erase a payment that really happened.
     * This test is the thing standing between that rule and a well-meaning future change.
     */
    @Test
    void refusesToCancelAProcessingIntent() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        attach(merchantId, intentId, key(), "CARD").andExpect(status().isOk());
        confirm(merchantId, intentId, key()).andExpect(status().isAccepted());

        cancel(merchantId, intentId, key())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CANCELLABLE"));

        mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).with(callerFor(merchantId)))
            .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void cancelsAnIntentAwaitingAPaymentMethod() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        cancel(merchantId, intentId, key())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(intentId))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationReason").value("customer changed mind"))
            .andExpect(jsonPath("$.cancelledAt").exists());
    }

    /**
     * The two dedup rules, side by side. A SECOND cancel on a FRESH key is a genuinely new request
     * and must meet the state machine: 409. The same request replayed on the SAME key never reaches
     * the state machine at all and gets the original 200 back.
     */
    @Test
    void refusesASecondCancellationOnAFreshKeyButReplaysItOnTheSameKey() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));
        String firstKey = key();

        cancel(merchantId, intentId, firstKey).andExpect(status().isOk());

        cancel(merchantId, intentId, key())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CANCELLABLE"));

        cancel(merchantId, intentId, firstKey)
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void refusesToCancelAnotherMerchantsIntent() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        cancel(otherMerchantId, intentId, key())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
    }

    // --- idempotency ---------------------------------------------------------------------------

    @Test
    void rejectsACreateWithNoIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/payment-intents")
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"orderId": "%s", "amountMinor": 1999, "currency": "INR"}
                    """.formatted(createOrder(merchantId))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void rejectsACancelWithNoIdempotencyKey() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        mockMvc.perform(post("/api/v1/payment-intents/{id}/cancel", intentId)
                .with(callerFor(merchantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /** A network retry must cost nothing: the same key and body returns the original intent. */
    @Test
    void replaysTheOriginalResponseForARetriedCreate() throws Exception {
        String idempotencyKey = key();
        String body = """
            {"orderId": "%s", "amountMinor": 1999, "currency": "INR"}
            """.formatted(createOrder(merchantId));

        MvcResult first = mockMvc.perform(newIntent(merchantId, idempotencyKey, body))
            .andExpect(status().isCreated())
            .andReturn();

        MvcResult retry = mockMvc.perform(newIntent(merchantId, idempotencyKey, body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andReturn();

        assertThat(retry.getResponse().getContentAsString())
            .as("a retry must not create a second intent")
            .isEqualTo(first.getResponse().getContentAsString());

        mockMvc.perform(get("/api/v1/payment-intents").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void rejectsTheSameIdempotencyKeyCarryingADifferentBody() throws Exception {
        String idempotencyKey = key();
        String orderId = createOrder(merchantId);

        mockMvc.perform(newIntent(merchantId, idempotencyKey, """
                {"orderId": "%s", "amountMinor": 1999, "currency": "INR"}
                """.formatted(orderId)))
            .andExpect(status().isCreated());

        mockMvc.perform(newIntent(merchantId, idempotencyKey, """
                {"orderId": "%s", "amountMinor": 1999, "currency": "INR", "description": "different"}
                """.formatted(orderId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static RequestPostProcessor callerFor(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }

    private MockHttpServletRequestBuilder newIntent(
        String merchantId,
        String idempotencyKey,
        String body
    ) {
        return post("/api/v1/payment-intents")
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private ResultActions attach(
        String merchantId,
        String intentId,
        String idempotencyKey,
        String paymentMethodType
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/payment-intents/{id}/payment-method", intentId)
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"paymentMethodType\": \"%s\"}".formatted(paymentMethodType)));
    }

    private ResultActions confirm(String merchantId, String intentId, String idempotencyKey)
        throws Exception {
        return mockMvc.perform(post("/api/v1/payment-intents/{id}/confirm", intentId)
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"returnUrl\": \"https://shop.test/return?session=SECRET\", \"device\": \"web\"}"));
    }

    private ResultActions cancel(String merchantId, String intentId, String idempotencyKey)
        throws Exception {
        return mockMvc.perform(post("/api/v1/payment-intents/{id}/cancel", intentId)
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\": \"customer changed mind\"}"));
    }

    /** The 422 body for an order that cannot be collected against, whatever the reason. */
    private String notPayable(String orderId) throws Exception {
        return mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": 1999, "currency": "INR"}
                """.formatted(orderId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    private String notFound(String callerMerchantId, String intentId) throws Exception {
        return mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId)
                .with(callerFor(callerMerchantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    private String createOrder(String merchantId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                .with(callerFor(merchantId))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amountMinor": %d, "currency": "INR"}
                    """.formatted(ORDER_AMOUNT_MINOR)))
            .andExpect(status().isCreated())
            .andReturn();

        return json(result).get("id").asText();
    }

    private String createIntent(String merchantId, String orderId) throws Exception {
        MvcResult result = mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": %d, "currency": "INR"}
                """.formatted(orderId, ORDER_AMOUNT_MINOR)))
            .andExpect(status().isCreated())
            .andReturn();

        return json(result).get("id").asText();
    }

    private String registerMerchant() {
        return merchants.register(new RegisterMerchantCommand(
            "Tenant Co", "tenant-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId().value();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }
}
