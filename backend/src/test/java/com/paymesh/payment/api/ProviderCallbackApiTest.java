package com.paymesh.payment.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE HMAC IS THE AUTHENTICATION, and this class is what says so. Everything here runs through the
 * real filter chain: Spring Security declares the route {@code permitAll()}, and
 * {@code ProviderCallbackSignatureFilter} is the only thing standing in front of it.
 * <p>
 * The status codes are the other half. <b>Every outcome answers 200, including the refusals</b> --
 * a provider retries on any non-2xx, and a 409 for a superseded event is an infinite retry loop
 * against a payment that is already finished (ADR-012). The single exception is the 404 for an
 * unknown intent, which is a deliberate request to retry.
 * <p>
 * Not {@code @Transactional}: these are real commits, and each test registers its own merchant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ProviderCallbackApiTest {

    private static final String PATH = "/internal/v1/provider-callbacks/SIMULATOR";
    private static final String SIGNATURE_HEADER = "X-PayMesh-Signature";

    /** application-dev.yaml's value. Public by definition; DevelopmentSecretGuard refuses it in prod. */
    private static final String DEV_SECRET = "dev-only-insecure-provider-callback-secret-change-me";

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30Z");
    private static final long ORDER_AMOUNT_MINOR = 1999;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private JdbcTemplate jdbc;

    // --- the signature ----------------------------------------------------------

    @Test
    void acceptsACallbackSignedWithTheSharedSecret() throws Exception {
        String body = succeededBody(processingIntent(), eventId());

        mockMvc.perform(signed(body, Instant.now()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("APPLIED"));
    }

    @Test
    void refusesACallbackWithNoSignatureAtAll() throws Exception {
        String intentId = processingIntent();

        mockMvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(succeededBody(intentId, eventId())))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("PROVIDER_SIGNATURE_INVALID"));

        assertThat(callbacksFor(intentId)).as("an unverified body reaches nothing").isZero();
    }

    @Test
    void refusesACallbackWhoseSignatureIsWrong() throws Exception {
        String intentId = processingIntent();
        String body = succeededBody(intentId, eventId());
        long now = Instant.now().getEpochSecond();

        mockMvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(SIGNATURE_HEADER, "t=" + now + ",v1=" + hmac("not-the-secret", now + "." + body)))
            .andExpect(status().isUnauthorized());

        assertThat(callbacksFor(intentId)).isZero();
    }

    /**
     * THE REPLAY-ONTO-A-DIFFERENT-BODY CASE, which is why the body is inside the signed string. A
     * captured signature that could be pasted onto a new body would let anyone who ever saw one
     * callback mark any payment succeeded.
     */
    @Test
    void refusesASignatureTakenOverADifferentBody() throws Exception {
        String intentId = processingIntent();
        String signedBody = succeededBody(intentId, eventId());
        String sentBody = succeededBody(intentId, eventId());
        long now = Instant.now().getEpochSecond();

        mockMvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sentBody)
                .header(SIGNATURE_HEADER, "t=" + now + ",v1=" + hmac(DEV_SECRET, now + "." + signedBody)))
            .andExpect(status().isUnauthorized());

        assertThat(callbacksFor(intentId)).isZero();
    }

    /**
     * THE REPLAY-LATER CASE, and the reason the timestamp is signed rather than merely sent: a
     * signature valid forever is a credential, and rewriting an unsigned {@code t} to refresh it
     * would be free.
     */
    @Test
    void refusesAPerfectlyValidSignatureThatIsTooOld() throws Exception {
        String intentId = processingIntent();

        mockMvc.perform(signed(succeededBody(intentId, eventId()), Instant.now().minusSeconds(400)))
            .andExpect(status().isUnauthorized());

        assertThat(callbacksFor(intentId)).isZero();
    }

    /** Both directions. A future stamp is one minted to outlive the window, not clock drift. */
    @Test
    void refusesASignatureStampedTooFarInTheFuture() throws Exception {
        String intentId = processingIntent();

        mockMvc.perform(signed(succeededBody(intentId, eventId()), Instant.now().plusSeconds(400)))
            .andExpect(status().isUnauthorized());

        assertThat(callbacksFor(intentId)).isZero();
    }

    /**
     * A MERCHANT'S TOKEN BUYS NOTHING HERE, AND THAT IS THE POINT OF THE WHOLE DESIGN. This route
     * moves payments to SUCCEEDED; a merchant able to call it could mark their own payment collected.
     * The token is accepted by the chain -- the route is permitAll -- and refused by the filter,
     * because the signature is the authentication and a bearer token is not one of the ways to
     * produce it.
     */
    @Test
    void refusesACallbackAuthenticatedWithNothingButAMerchantToken() throws Exception {
        String intentId = processingIntent();

        mockMvc.perform(post(PATH)
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(succeededBody(intentId, eventId())))
            .andExpect(status().isUnauthorized());

        assertThat(callbacksFor(intentId)).isZero();
    }

    // --- the status codes ---------------------------------------------------------

    /** 200, not 409. A conflict here is an infinite retry loop against a finished payment. */
    @Test
    void answers200WithDuplicateWhenTheSameEventArrivesTwice() throws Exception {
        String body = succeededBody(processingIntent(), eventId());

        mockMvc.perform(signed(body, Instant.now()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("APPLIED"));
        mockMvc.perform(signed(body, Instant.now()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
    }

    /**
     * THE ONE NON-2XX, and it is a deliberate request to retry: the likeliest cause is a callback
     * overtaking the transaction that created the intent. Nothing is stored, because there is
     * nothing to deduplicate for an event that had no effect.
     */
    @Test
    void answers404AndStoresNothingForAnIntentThatDoesNotExist() throws Exception {
        String ghost = "pi_" + UUID.randomUUID();

        mockMvc.perform(signed(succeededBody(ghost, eventId()), Instant.now()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));

        assertThat(callbacksFor(ghost)).isZero();
    }

    @Test
    void answers400ForAnOutcomeNoProviderCanReport() throws Exception {
        String body = """
            {
              "eventId": "%s",
              "occurredAt": "2026-08-01T11:00:00Z",
              "paymentIntentId": "%s",
              "outcome": "REFUNDED",
              "capturedAmountMinor": 1999
            }
            """.formatted(eventId(), processingIntent());

        mockMvc.perform(signed(body, Instant.now()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void answers400WhenTheBodyNamesNeitherAnIntentNorAProviderReference() throws Exception {
        String body = """
            {
              "eventId": "%s",
              "occurredAt": "2026-08-01T11:00:00Z",
              "outcome": "SUCCEEDED",
              "capturedAmountMinor": 1999
            }
            """.formatted(eventId());

        mockMvc.perform(signed(body, Instant.now()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void answers400WhenTheEventCarriesNoTimestamp() throws Exception {
        String body = """
            {
              "eventId": "%s",
              "paymentIntentId": "%s",
              "outcome": "SUCCEEDED",
              "capturedAmountMinor": 1999
            }
            """.formatted(eventId(), processingIntent());

        mockMvc.perform(signed(body, Instant.now()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.occurredAt").exists());
    }

    /**
     * A superseded event is 200 IGNORED_TERMINAL, delivered over HTTP rather than only asserted at
     * the service. The status code is the part a provider's retry logic reads.
     */
    @Test
    void answers200WithIgnoredTerminalForACallbackAgainstAFinishedPayment() throws Exception {
        String intentId = processingIntent();

        mockMvc.perform(signed(succeededBody(intentId, eventId()), Instant.now()))
            .andExpect(status().isOk());

        String late = """
            {
              "eventId": "%s",
              "occurredAt": "2026-08-01T12:00:00Z",
              "paymentIntentId": "%s",
              "outcome": "FAILED",
              "failureCode": "do_not_honour"
            }
            """.formatted(eventId(), intentId);

        mockMvc.perform(signed(late, Instant.now()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("IGNORED_TERMINAL"));
    }

    // --- helpers --------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
        String body,
        Instant stampedAt
    ) {
        long timestamp = stampedAt.getEpochSecond();

        return post(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .header(SIGNATURE_HEADER,
                "t=" + timestamp + ",v1=" + hmac(DEV_SECRET, timestamp + "." + body));
    }

    private static String hmac(String secret, String signedString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            return HexFormat.of()
                .formatHex(mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String eventId() {
        return "sim_evt_" + UUID.randomUUID();
    }

    private static String succeededBody(String paymentIntentId, String eventId) {
        return """
            {
              "eventId": "%s",
              "occurredAt": "2026-08-01T11:00:00Z",
              "paymentIntentId": "%s",
              "providerReference": "sim_pay_%s",
              "outcome": "SUCCEEDED",
              "capturedAmountMinor": 1999
            }
            """.formatted(eventId, paymentIntentId, UUID.randomUUID());
    }

    /** A merchant, an order, an intent with a method attached, confirmed: PROCESSING. */
    private String processingIntent() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();

        String orderId = orders.save(Order.create(
            OrderId.generate(), merchantId, null, null, ORDER_AMOUNT_MINOR, "INR", null,
            Map.of(), null, CREATED_AT
        )).orderId().value();

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, orderId, null, ORDER_AMOUNT_MINOR, "INR", null, null, Map.of()
        ));

        attachPaymentMethodService.attach(merchantId, intent.paymentIntentId(), PaymentMethodType.CARD);
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        return intent.paymentIntentId().value();
    }

    /**
     * Scoped to the intent under test, not the whole table. Every test here mints its own intent, so
     * this isolates them from each other -- the container is shared and these are real commits.
     */
    private long callbacksFor(String paymentIntentId) {
        return jdbc.queryForObject(
            "select count(*) from provider_callbacks where payment_intent_id = ?",
            Long.class,
            paymentIntentId
        );
    }
}
