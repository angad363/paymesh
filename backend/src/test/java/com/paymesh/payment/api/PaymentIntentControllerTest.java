package com.paymesh.payment.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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

    /** Platform staff, for fixtures that must activate a merchant. */
    private static final String PLATFORM_OPERATOR = "usr_00000000-0000-4000-8000-000000000001";

    private static final long ORDER_AMOUNT_MINOR = 1999;

    private final MockMvc mockMvc;
    private final RegisterMerchantService merchants;

    private final ChangeMerchantStatusService changeMerchantStatus;

    /**
     * Reached directly rather than over HTTP, and only to set up an AUTHORIZED intent.
     * <p>
     * The callback route is HMAC-signed and its contract is {@code ProviderCallbackApiTest}'s
     * subject; recomputing a signature here to reach a precondition would be testing that filter a
     * second time, badly. Capture's own HTTP contract -- the statuses, the codes, the idempotency --
     * is what this class is for, and every capture assertion below goes through MockMvc.
     */
    private final RecordProviderCallbackService callbacks;

    private String merchantId;
    private String otherMerchantId;

    @Autowired
    PaymentIntentControllerTest(
        MockMvc mockMvc,
        RegisterMerchantService merchants,
        ChangeMerchantStatusService changeMerchantStatus,
        RecordProviderCallbackService callbacks
    ) {
        this.mockMvc = mockMvc;
        this.merchants = merchants;
        this.changeMerchantStatus = changeMerchantStatus;
        this.callbacks = callbacks;
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


    // --- manual capture (design spec section 5) --------------------------------------------------

    /**
     * 200 AND NOT 202, WHICH IS THE OPPOSITE OF CONFIRM AND DELIBERATE. Confirm's outcome is
     * undecided until a callback resolves it; capture's is decided by the time the response is
     * written. That changes when a real provider makes capture asynchronous, and the status code
     * changes with it.
     */
    @Test
    void capturesTheFullAuthorizedAmountByDefault() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        capture(merchantId, intentId, key(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(ORDER_AMOUNT_MINOR))
            .andExpect(jsonPath("$.amountMinor").value(ORDER_AMOUNT_MINOR));
    }

    /**
     * PARTIAL CAPTURE STILL SUCCEEDS, with captured below authorized. The gap is what will make
     * {@code orders.PARTIALLY_PAID} reachable through the {@code payment.succeeded} consumer that
     * does not exist yet.
     */
    @Test
    void capturesLessThanAuthorizedAndStillReportsSucceeded() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        capture(merchantId, intentId, key(), 500L)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(500))
            .andExpect(jsonPath("$.amountMinor").value(ORDER_AMOUNT_MINOR));
    }

    /**
     * OVERCAPTURE IS 422, AND THIS TEST IS ALSO THE CHECK CONSTRAINT'S ALARM.
     * <p>
     * {@code ck_payment_intents_captured} is the guarantee; the aggregate's check exists so the
     * merchant gets a readable answer instead of a constraint name. Delete that check and this test
     * goes red with a 500, because the database takes the refusal instead -- which is the CHECK
     * working and the API contract broken.
     */
    @Test
    void refusesToCaptureMoreThanTheAuthorizedAmount() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        capture(merchantId, intentId, key(), ORDER_AMOUNT_MINOR + 1)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CAPTURE_AMOUNT_EXCEEDS_AUTHORIZED"));

        mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).with(callerFor(merchantId)))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(0));
    }

    /** Zero and negative are malformed rather than merely impossible, so they are 400 at the boundary. */
    @Test
    void rejectsANonPositiveCaptureAmountAtTheBoundary() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        capture(merchantId, intentId, key(), 0L).andExpect(status().isBadRequest());
        capture(merchantId, intentId, key(), -1L).andExpect(status().isBadRequest());
    }

    /**
     * 409 FROM A STATE THAT HOLDS NOTHING. Retrying the identical request will never succeed, which
     * is what separates this from a 400.
     */
    @Test
    void refusesToCaptureAnIntentThatIsNotAuthorized() throws Exception {
        String intentId = createIntent(merchantId, createOrder(merchantId));

        capture(merchantId, intentId, key(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CAPTURABLE"));

        attach(merchantId, intentId, key(), "CARD").andExpect(status().isOk());
        confirm(merchantId, intentId, key()).andExpect(status().isAccepted());

        capture(merchantId, intentId, key(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CAPTURABLE"));
    }

    /** An AUTOMATIC intent is the provider's to capture; the message says which of the two it was. */
    @Test
    void refusesToCaptureAnAutomaticIntent() throws Exception {
        String intentId = authorizedIntent(merchantId, "AUTOMATIC");

        capture(merchantId, intentId, key(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CAPTURABLE"))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("captured by the provider")
            ));
    }

    /**
     * A RETRIED CAPTURE MUST REPLAY, NOT CONFLICT. This is the route on the idempotent list that most
     * needs to be there: a network retry of a capture that already committed should get the same 200,
     * not a 409 telling the merchant a collection they asked for once cannot be asked for again.
     */
    @Test
    void replaysACaptureRetriedOnTheSameKey() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");
        String idempotencyKey = key();

        capture(merchantId, intentId, idempotencyKey, null).andExpect(status().isOk());

        capture(merchantId, intentId, idempotencyKey, null)
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    /** A genuinely new request to capture an already-captured intent is a conflict, and should be. */
    @Test
    void refusesASecondCaptureOnAFreshKey() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        capture(merchantId, intentId, key(), null).andExpect(status().isOk());

        capture(merchantId, intentId, key(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CAPTURABLE"));
    }

    @Test
    void refusesACaptureWithNoIdempotencyKey() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        mockMvc.perform(post("/api/v1/payment-intents/{id}/capture", intentId)
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /** A pi_ in a path authorizes nothing. 404, never 403 -- the answer must not confirm it exists. */
    @Test
    void refusesToCaptureAnotherMerchantsIntent() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        capture(otherMerchantId, intentId, key(), null)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).with(callerFor(merchantId)))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    /**
     * AUTHORIZED TO CANCELLED, which ADR-011's slot table requires: a MANUAL intent parked at
     * AUTHORIZED is a state a merchant can sit in indefinitely, and without this route the order's
     * only slot is held forever by funds nobody intends to take.
     */
    @Test
    void cancelsAnAuthorizedIntentRatherThanCapturingIt() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");

        cancel(merchantId, intentId, key())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(0));
    }

    /** Captured funds cannot be un-captured by cancelling. Reversal belongs to the Refund capability. */
    @Test
    void refusesToCancelACapturedIntent() throws Exception {
        String intentId = authorizedIntent(merchantId, "MANUAL");
        capture(merchantId, intentId, key(), null).andExpect(status().isOk());

        cancel(merchantId, intentId, key())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_CANCELLABLE"));
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * A capture request. The body is always sent, because an absent {@code amountMinor} and an absent
     * body are two different things the handler has to survive -- {@code null} here sends {@code {}}.
     */
    private ResultActions capture(
        String merchantId,
        String intentId,
        String idempotencyKey,
        Long amountMinor
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/payment-intents/{id}/capture", intentId)
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(amountMinor == null ? "{}" : "{\"amountMinor\": %d}".formatted(amountMinor)));
    }

    /**
     * An order, an intent, a method, a confirm and an AUTHORIZED provider outcome -- the precondition
     * capture needs and the only one that cannot be reached over the merchant-facing API, because
     * only a provider can authorize.
     */
    private String authorizedIntent(String merchantId, String captureMethod) throws Exception {
        MvcResult created = mockMvc.perform(newIntent(merchantId, key(), """
                {"orderId": "%s", "amountMinor": %d, "currency": "INR", "captureMethod": "%s"}
                """.formatted(createOrder(merchantId), ORDER_AMOUNT_MINOR, captureMethod)))
            .andExpect(status().isCreated())
            .andReturn();

        String intentId = json(created).get("id").asText();

        attach(merchantId, intentId, key(), "CARD").andExpect(status().isOk());
        confirm(merchantId, intentId, key()).andExpect(status().isAccepted());

        String eventId = "sim_evt_" + UUID.randomUUID();

        callbacks.record(new RecordProviderCallbackCommand(
            "SIMULATOR",
            new ProviderEvent(
                eventId, Instant.now(), intentId, "sim_pay_" + UUID.randomUUID(),
                ProviderOutcome.AUTHORIZED, ORDER_AMOUNT_MINOR, null, null, null, null
            ),
            payloadHash(eventId)
        ));

        mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).with(callerFor(merchantId)))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        return intentId;
    }

    /** Stands in for the signature filter's hash of the raw body: 64 hex characters, distinct. */
    private static String payloadHash(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

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
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Tenant Co", "tenant-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();
        activate(merchantId);

        return merchantId.value();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    /**
     * REGISTRATION PRODUCES PENDING_VERIFICATION, AND A PENDING MERCHANT CANNOT WRITE (ADR-021).
     * <p>
     * Every fixture that goes on to create an order, an intent or a refund has to take the merchant
     * through the real activation path first, exactly as a platform operator would. Skipping it
     * would mean these tests exercised a merchant state no live merchant can be in.
     */
    private void activate(MerchantId merchantId) {
        changeMerchantStatus.activate(merchantId, PLATFORM_OPERATOR, "Activated for test");
    }
}
