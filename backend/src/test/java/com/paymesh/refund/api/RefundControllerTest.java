package com.paymesh.refund.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/refunds}.
 * <p>
 * Not {@code @Transactional}: the create route runs behind the idempotency filter, whose record has
 * to genuinely commit before the handler runs. Each test makes its own merchant and payment.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RefundControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final long CAPTURED = 99900;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService paymentCallbacks;

    // --- create -----------------------------------------------------------------------------------

    @Test
    void createsARefundAgainstACollectedPayment() throws Exception {
        MerchantId merchantId = registerMerchant();
        String intentId = collected(merchantId);

        mockMvc.perform(newRefund(merchantId, key(), """
                { "paymentIntentId": "%s", "amountMinor": 30000, "reason": "Changed their mind" }
                """.formatted(intentId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("ref_")))
            .andExpect(jsonPath("$.paymentIntentId").value(intentId))
            .andExpect(jsonPath("$.amountMinor").value(30000))
            .andExpect(jsonPath("$.currency").value("INR"))
            .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    /** Omitting the amount refunds the whole capture. */
    @Test
    void refundsEverythingWhenNoAmountIsGiven() throws Exception {
        MerchantId merchantId = registerMerchant();
        String intentId = collected(merchantId);

        mockMvc.perform(newRefund(merchantId, key(), """
                { "paymentIntentId": "%s" }
                """.formatted(intentId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amountMinor").value(CAPTURED));
    }

    /** 422 with all three figures, so a merchant can compute what would fit. */
    @Test
    void refusesMoreThanWasCaptured() throws Exception {
        MerchantId merchantId = registerMerchant();
        String intentId = collected(merchantId);

        mockMvc.perform(newRefund(merchantId, key(), """
                { "paymentIntentId": "%s", "amountMinor": %d }
                """.formatted(intentId, CAPTURED + 1)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_CAPTURED_AMOUNT"));
    }

    /**
     * ANOTHER MERCHANT'S PAYMENT IS 422 PAYMENT_NOT_REFUNDABLE, byte for byte what a payment that
     * never existed returns. A 403 or a different code would confirm the payment is real.
     */
    @Test
    void refusesAnotherMerchantsPaymentIndistinguishablyFromOneThatDoesNotExist() throws Exception {
        MerchantId owner = registerMerchant();
        MerchantId stranger = registerMerchant();
        String intentId = collected(owner);

        mockMvc.perform(newRefund(stranger, key(), """
                { "paymentIntentId": "%s", "amountMinor": 100 }
                """.formatted(intentId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"));

        mockMvc.perform(newRefund(stranger, key(), """
                { "paymentIntentId": "pi_%s", "amountMinor": 100 }
                """.formatted(UUID.randomUUID())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"));
    }

    @Test
    void refusesAPaymentThatCollectedNothing() throws Exception {
        MerchantId merchantId = registerMerchant();
        String intentId = confirmedButNotCollected(merchantId);

        mockMvc.perform(newRefund(merchantId, key(), """
                { "paymentIntentId": "%s", "amountMinor": 100 }
                """.formatted(intentId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"));
    }

    @Test
    void refusesAZeroOrNegativeAmount() throws Exception {
        MerchantId merchantId = registerMerchant();
        String intentId = collected(merchantId);

        mockMvc.perform(newRefund(merchantId, key(), """
                { "paymentIntentId": "%s", "amountMinor": 0 }
                """.formatted(intentId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());
    }

    @Test
    void refusesAMalformedPaymentIntentIdentifier() throws Exception {
        mockMvc.perform(newRefund(registerMerchant(), key(), """
                { "paymentIntentId": "not-an-id", "amountMinor": 100 }
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.paymentIntentId").exists());
    }

    /** Registered as idempotent, so the filter requires the header. */
    @Test
    void refusesACreateWithNoIdempotencyKey() throws Exception {
        MerchantId merchantId = registerMerchant();

        mockMvc.perform(post("/api/v1/refunds")
                .with(callerFor(merchantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "paymentIntentId": "pi_%s", "amountMinor": 100 }
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /**
     * THE RETRY THAT MATTERS MOST HERE. Capture is protected by its state machine as well; a second
     * refund of one payment is perfectly legal, so a replayed network retry is the only thing
     * standing between one refund and two.
     */
    @Test
    void replaysTheStoredResponseOnARetryOfTheSameKey() throws Exception {
        MerchantId merchantId = registerMerchant();
        String intentId = collected(merchantId);
        String key = key();
        String body = """
            { "paymentIntentId": "%s", "amountMinor": 30000 }
            """.formatted(intentId);

        String first = mockMvc.perform(newRefund(merchantId, key, body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        mockMvc.perform(newRefund(merchantId, key, body))
            // THE ORIGINAL STATUS, not 200. The filter replays the stored response verbatim, so a
            // retry of a create is indistinguishable from the create -- which is the point: a
            // caller retrying a timed-out request must not have to handle a second status code.
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(content().json(first));
    }

    // --- read -------------------------------------------------------------------------------------

    @Test
    void readsARefundBack() throws Exception {
        MerchantId merchantId = registerMerchant();
        String refundId = createRefund(merchantId, collected(merchantId), 30000);

        mockMvc.perform(get("/api/v1/refunds/" + refundId).with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(refundId));
    }

    /** 404, not 403 -- a 403 would confirm the refund exists (ADR-007). */
    @Test
    void hidesAnotherMerchantsRefundBehindA404() throws Exception {
        MerchantId owner = registerMerchant();
        String refundId = createRefund(owner, collected(owner), 30000);

        mockMvc.perform(get("/api/v1/refunds/" + refundId).with(callerFor(registerMerchant())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));
    }

    @Test
    void refusesAMalformedRefundIdentifier() throws Exception {
        mockMvc.perform(get("/api/v1/refunds/not_a_refund").with(callerFor(registerMerchant())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listsOnlyTheCallersRefunds() throws Exception {
        MerchantId owner = registerMerchant();
        createRefund(owner, collected(owner), 30000);

        mockMvc.perform(get("/api/v1/refunds").with(callerFor(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/refunds").with(callerFor(registerMerchant())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void pagesWithoutRepeatingOrSkipping() throws Exception {
        MerchantId merchantId = registerMerchant();
        String intentId = collected(merchantId);
        createRefund(merchantId, intentId, 10000);
        createRefund(merchantId, intentId, 20000);

        String cursor = mockMvc.perform(
                get("/api/v1/refunds").param("limit", "1").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").exists())
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/refunds")
                .param("limit", "1").param("cursor", cursor).with(callerFor(merchantId)))
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void refusesALimitBelowOne() throws Exception {
        mockMvc.perform(get("/api/v1/refunds").param("limit", "0")
                .with(callerFor(registerMerchant())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void refusesACorruptCursor() throws Exception {
        mockMvc.perform(get("/api/v1/refunds").param("cursor", "!!not-base64!!")
                .with(callerFor(registerMerchant())))
            .andExpect(status().isBadRequest());
    }

    // --- cancel -----------------------------------------------------------------------------------

    /**
     * A refund is PROCESSING by the time the caller sees it, so 409 is the ordinary answer. That is
     * the honest behaviour: PROCESSING means the provider may already have moved the money.
     */
    @Test
    void refusesToCancelARefundTheProviderAlreadyHas() throws Exception {
        MerchantId merchantId = registerMerchant();
        String refundId = createRefund(merchantId, collected(merchantId), 30000);

        mockMvc.perform(post("/api/v1/refunds/" + refundId + "/cancel")
                .with(callerFor(merchantId))
                .header("Idempotency-Key", key()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REFUND_NOT_CANCELLABLE"));
    }

    // --- auth -------------------------------------------------------------------------------------

    @Test
    void refusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/v1/refunds")).andExpect(status().isUnauthorized());
    }

    @Test
    void refusesACallerWithNoMerchantScope() throws Exception {
        RequestPostProcessor scopeless = jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of()));

        mockMvc.perform(get("/api/v1/refunds").with(scopeless))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    // --- helpers ----------------------------------------------------------------------------------

    private String createRefund(MerchantId merchantId, String intentId, long amountMinor)
        throws Exception {
        return mockMvc.perform(newRefund(merchantId, key(), """
                { "paymentIntentId": "%s", "amountMinor": %d }
                """.formatted(intentId, amountMinor)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"id\":\"(ref_[^\"]+)\".*", "$1");
    }

    private MockHttpServletRequestBuilder newRefund(
        MerchantId merchantId,
        String idempotencyKey,
        String body
    ) {
        return post("/api/v1/refunds")
            .with(callerFor(merchantId))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    /** An order, an intent, confirmed and collected in full. */
    private String collected(MerchantId merchantId) {
        String intentId = confirmedButNotCollected(merchantId);

        paymentCallbacks.record(new RecordProviderCallbackCommand(
            "SIMULATOR",
            new ProviderEvent(
                "evt-" + UUID.randomUUID(), PROVIDER_EVENT, intentId,
                null, ProviderOutcome.SUCCEEDED, null, CAPTURED, null, null, null
            ),
            (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "")
        ));

        return intentId;
    }

    private String confirmedButNotCollected(MerchantId merchantId) {
        Order order = orders.save(Order.create(
            OrderId.generate(), merchantId, null, "ORDER-" + UUID.randomUUID(),
            CAPTURED, "INR", null, Map.of(), null, CREATED_AT
        ));

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, order.orderId().value(), null, CAPTURED, "INR",
            CaptureMethod.AUTOMATIC, null, Map.of()
        ));

        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        return intent.paymentIntentId().value();
    }

    private static RequestPostProcessor callerFor(MerchantId merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId.value())));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private MerchantId registerMerchant() {
        return merchants.register(new RegisterMerchantCommand(
            "Refund Test Co", "refund-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();
    }
}
