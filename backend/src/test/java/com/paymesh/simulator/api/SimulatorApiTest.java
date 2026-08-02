package com.paymesh.simulator.api;

import com.paymesh.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE API KEY IS THE AUTHENTICATION, and this class is what says so. Everything runs through the
 * real filter chain: Spring Security declares {@code /sim/v1/**} {@code permitAll()}, and
 * {@code SimulatorApiKeyFilter} is the only thing standing in front of it.
 * <p>
 * Nothing here registers a merchant, mints a token or names a tenant, and that absence is the point
 * -- the simulator has never been told PayMesh has tenants.
 * <p>
 * Not {@code @Transactional}: these are real commits against the container, and each test mints its
 * own idempotency keys so they cannot collide through
 * {@code uq_provider_payments_idempotency_key}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SimulatorApiTest {

    private static final String PAYMENTS = "/sim/v1/payments";
    private static final String KEY_HEADER = "X-PayMesh-Simulator-Key";

    /** application-dev.yaml's value. Public by definition; DevelopmentSecretGuard refuses it in prod. */
    private static final String DEV_KEY = "dev-only-insecure-simulator-api-key-change-me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------- the key

    @Test
    void refusesARequestCarryingNoSimulatorKey() throws Exception {
        mockMvc.perform(post(PAYMENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(freshKey(), 1999)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SIMULATOR_KEY_INVALID"));
    }

    @Test
    void refusesARequestCarryingTheWrongSimulatorKey() throws Exception {
        mockMvc.perform(post(PAYMENTS)
                .header(KEY_HEADER, "not-the-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(freshKey(), 1999)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SIMULATOR_KEY_INVALID"));
    }

    /**
     * THE ONE THAT WOULD BE EASY TO GET WRONG. A merchant's access token must not be a way in: this
     * route queues a callback that marks a payment SUCCEEDED, so a merchant able to call it could
     * authorize their own collection. The token is ignored entirely and the key is still required.
     */
    @Test
    void refusesAMerchantAccessTokenAsAWayIn() throws Exception {
        mockMvc.perform(post(PAYMENTS)
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(freshKey(), 1999)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SIMULATOR_KEY_INVALID"));
    }

    // ------------------------------------------------------------- create

    @Test
    void takesAPaymentAndCapturesItWhenTheProviderCapturesAutomatically() throws Exception {
        mockMvc.perform(simulator(post(PAYMENTS)).content(createBody(freshKey(), 1999)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.providerPaymentId").value(org.hamcrest.Matchers.startsWith("sim_pay_")))
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.behaviour").value("SUCCEED"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(1999))
            .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void stopsAtAuthorizedWhenTheProviderIsAskedToWait() throws Exception {
        mockMvc.perform(simulator(post(PAYMENTS)).content(createBody(freshKey(), 1999, "tok_sim_success", "MANUAL")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("AUTHORIZED"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(0));
    }

    @Test
    void declinesThePaymentWhenTheTokenAsksForADecline() throws Exception {
        mockMvc.perform(simulator(post(PAYMENTS)).content(createBody(freshKey(), 1999, "tok_sim_decline", "AUTOMATIC")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DECLINED"))
            .andExpect(jsonPath("$.failureCode").value("do_not_honour"));
    }

    /**
     * THE LOST CALLBACK, and the assertion that matters is the second one: no outbound row at all.
     * Anything else would be a callback that eventually arrives, which is the opposite of what
     * {@code tok_sim_timeout} is for.
     */
    @Test
    void writesNoCallbackAtAllForATimedOutPayment() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS))
                    .content(createBody(freshKey(), 1999, "tok_sim_timeout", "AUTOMATIC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TIMED_OUT"))
                .andReturn().getResponse().getContentAsString()
        );

        assertThat(callbackCountFor(id)).isZero();
    }

    @Test
    void writesTwoCallbacksSharingOneEventIdForTheDuplicateBehaviour() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS))
                    .content(createBody(freshKey(), 1999, "tok_sim_duplicate", "AUTOMATIC")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
        );

        assertThat(callbackCountFor(id)).isEqualTo(2);
        assertThat(distinctEventIdsFor(id))
            .as("two rows sharing one external_event_id IS the duplicate scenario")
            .isEqualTo(1);
    }

    @Test
    void writesTwoCallbacksWithDistinctEventIdsForTheStaleBehaviour() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS))
                    .content(createBody(freshKey(), 1999, "tok_sim_stale", "AUTOMATIC")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
        );

        assertThat(callbackCountFor(id)).isEqualTo(2);
        assertThat(distinctEventIdsFor(id)).isEqualTo(2);
    }

    // -------------------------------------------------------- idempotency

    /**
     * THE BODY IS BUILT ONCE AND POSTED TWICE, WHICH IS THE WHOLE TEST.
     * <p>
     * {@code createBody} mints a fresh {@code callbackReference} on every call, and the request hash
     * covers it -- so re-calling the helper would produce a genuinely different request and a correct
     * 409, and this test would then pass or fail for a reason unrelated to replay. The first draft of
     * this test did exactly that and reported a 409 as a replay bug.
     */
    @Test
    void replaysTheOriginalPaymentWithTwoHundredForARepeatedKey() throws Exception {
        String body = createBody(freshKey(), 1999);

        String first = mockMvc.perform(simulator(post(PAYMENTS)).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        mockMvc.perform(simulator(post(PAYMENTS)).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providerPaymentId").value(providerPaymentIdOf(first)));
    }

    /** One row, however many times the identical request arrives. The unique constraint is the guard. */
    @Test
    void createsExactlyOneRowHoweverManyTimesTheIdenticalRequestArrives() throws Exception {
        String body = createBody(freshKey(), 1999);

        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS)).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
        );

        mockMvc.perform(simulator(post(PAYMENTS)).content(body)).andExpect(status().isOk());
        mockMvc.perform(simulator(post(PAYMENTS)).content(body)).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from provider_payments where provider_payment_id = ?",
            Integer.class, id
        )).isEqualTo(1);

        assertThat(callbackCountFor(id))
            .as("a replay must not enqueue a second callback either")
            .isEqualTo(1);
    }

    /**
     * SAME KEY, DIFFERENT REQUEST IS A 409 AND NOT "RETURN THE ORIGINAL".
     * <p>
     * Returning the original would be friendlier and is what some real providers do -- but the
     * original may be for a different amount, and answering "your 1999 payment succeeded" to a
     * request for 50000 is a money-path lie.
     */
    @Test
    void refusesARepeatedKeyCarryingADifferentRequest() throws Exception {
        String body = createBody(freshKey(), 1999);

        mockMvc.perform(simulator(post(PAYMENTS)).content(body))
            .andExpect(status().isCreated());

        // THE AMOUNT IS THE ONLY DIFFERENCE, deliberately. Re-minting the whole body would also
        // change the callback reference, and the test would then prove only that *something*
        // differed -- it would still be green if the hash ignored the amount entirely, which is the
        // one field whose being ignored would be a money-path lie.
        mockMvc.perform(simulator(post(PAYMENTS))
                .content(body.replace("\"amountMinor\": 1999", "\"amountMinor\": 50000")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SIMULATOR_IDEMPOTENCY_KEY_REUSED"));
    }

    // ------------------------------------------------------------ capture

    @Test
    void capturesAnAuthorizationTheProviderWasHolding() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS))
                    .content(createBody(freshKey(), 1999, "tok_sim_success", "MANUAL")))
                .andReturn().getResponse().getContentAsString()
        );

        mockMvc.perform(simulator(post(PAYMENTS + "/" + id + "/capture"))
                .content("{\"idempotencyKey\":\"" + freshKey() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.capturedAmountMinor").value(1999));
    }

    @Test
    void refusesToCaptureAPaymentTheProviderAlreadyCaptured() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS)).content(createBody(freshKey(), 1999)))
                .andReturn().getResponse().getContentAsString()
        );

        mockMvc.perform(simulator(post(PAYMENTS + "/" + id + "/capture"))
                .content("{\"idempotencyKey\":\"" + freshKey() + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SIMULATED_PAYMENT_NOT_CAPTURABLE"));
    }

    @Test
    void refusesToCaptureMoreThanWasAuthorized() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS))
                    .content(createBody(freshKey(), 1999, "tok_sim_success", "MANUAL")))
                .andReturn().getResponse().getContentAsString()
        );

        mockMvc.perform(simulator(post(PAYMENTS + "/" + id + "/capture"))
                .content("{\"idempotencyKey\":\"" + freshKey() + "\",\"amountMinor\":5000}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CAPTURE_EXCEEDS_AUTHORIZED_AMOUNT"));
    }

    @Test
    void answersNotFoundForAnUnknownSimulatedPayment() throws Exception {
        mockMvc.perform(simulator(post(PAYMENTS + "/sim_pay_" + UUID.randomUUID() + "/capture"))
                .content("{\"idempotencyKey\":\"" + freshKey() + "\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SIMULATED_PAYMENT_NOT_FOUND"));
    }

    @Test
    void answersBadRequestForAMalformedSimulatedPaymentId() throws Exception {
        mockMvc.perform(simulator(post(PAYMENTS + "/not-an-id/capture"))
                .content("{\"idempotencyKey\":\"" + freshKey() + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ------------------------------------------------------------- refund

    @Test
    void refundsAgainstWhatTheProviderCaptured() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS)).content(createBody(freshKey(), 1999)))
                .andReturn().getResponse().getContentAsString()
        );

        mockMvc.perform(simulator(post("/sim/v1/refunds")).content(
                "{\"idempotencyKey\":\"" + freshKey() + "\",\"providerPaymentId\":\"" + id
                    + "\",\"amountMinor\":999}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.providerRefundId").value(org.hamcrest.Matchers.startsWith("sim_ref_")))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.amountMinor").value(999));
    }

    @Test
    void refusesToRefundMoreThanTheProviderCaptured() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS)).content(createBody(freshKey(), 1999)))
                .andReturn().getResponse().getContentAsString()
        );

        mockMvc.perform(simulator(post("/sim/v1/refunds")).content(
                "{\"idempotencyKey\":\"" + freshKey() + "\",\"providerPaymentId\":\"" + id
                    + "\",\"amountMinor\":2000}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_CAPTURED_AMOUNT"));
    }

    // --------------------------------------------------- reconciliation

    /**
     * Asserted by containment rather than by count. Every test in this class commits to the same
     * container and the export is per UTC day, so an exact total would couple this assertion to how
     * many other tests happened to run first.
     */
    @Test
    void exportsThePaymentsTheProviderRecordedThatDay() throws Exception {
        String id = providerPaymentIdOf(
            mockMvc.perform(simulator(post(PAYMENTS)).content(createBody(freshKey(), 1999)))
                .andReturn().getResponse().getContentAsString()
        );

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        String report = mockMvc.perform(simulator(get("/sim/v1/reconciliation/" + today)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value(today.toString()))
            .andReturn().getResponse().getContentAsString();

        assertThat(report).contains(id);
    }

    @Test
    void answersAnEmptyReportForADayTheProviderTookNothing() throws Exception {
        mockMvc.perform(simulator(get("/sim/v1/reconciliation/2020-01-01")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.payments").isEmpty())
            .andExpect(jsonPath("$.capturedTotalMinor").value(0));
    }

    @Test
    void answersBadRequestForAMalformedReconciliationDate() throws Exception {
        mockMvc.perform(simulator(get("/sim/v1/reconciliation/not-a-date")))
            .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------- failure profile

    /**
     * Restored to SUCCEED at the end, because this row is ambient state shared by every test in the
     * class and leaving it on DECLINE would make the next one fail for a reason it never mentions.
     */
    @Test
    void appliesTheAmbientDefaultOnlyWhereATokenNamesNothing() throws Exception {
        try {
            mockMvc.perform(simulator(post("/sim/v1/failure-profile"))
                    .content("{\"defaultBehaviour\":\"DECLINE\",\"callbackDelayMs\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultBehaviour").value("DECLINE"));

            mockMvc.perform(simulator(post(PAYMENTS))
                    .content(createBody(freshKey(), 1999, "tok_unrecognised", "AUTOMATIC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"));

            // ...and the token still wins where it names something.
            mockMvc.perform(simulator(post(PAYMENTS))
                    .content(createBody(freshKey(), 1999, "tok_sim_success", "AUTOMATIC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        } finally {
            mockMvc.perform(simulator(post("/sim/v1/failure-profile"))
                .content("{\"defaultBehaviour\":\"SUCCEED\",\"callbackDelayMs\":0}"));
        }
    }

    @Test
    void refusesAnUnknownBehaviourOnTheFailureProfile() throws Exception {
        mockMvc.perform(simulator(post("/sim/v1/failure-profile"))
                .content("{\"defaultBehaviour\":\"EXPLODE\",\"callbackDelayMs\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void refusesANegativeCallbackDelay() throws Exception {
        mockMvc.perform(simulator(post("/sim/v1/failure-profile"))
                .content("{\"defaultBehaviour\":\"SUCCEED\",\"callbackDelayMs\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // -------------------------------------------------------- validation

    @Test
    void reportsMissingFieldsPerField() throws Exception {
        mockMvc.perform(simulator(post(PAYMENTS)).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.idempotencyKey").exists())
            .andExpect(jsonPath("$.fieldErrors.callbackReference").exists())
            .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());
    }

    @Test
    void refusesAnUnknownPaymentMethod() throws Exception {
        mockMvc.perform(simulator(post(PAYMENTS)).content(
                createBody(freshKey(), 1999).replace("\"CARD\"", "\"CHEQUE\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------ helpers

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder simulator(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder
    ) {
        return builder.header(KEY_HEADER, DEV_KEY).contentType(MediaType.APPLICATION_JSON);
    }

    private static String freshKey() {
        return "idem-" + UUID.randomUUID();
    }

    private static String createBody(String idempotencyKey, long amountMinor) {
        return createBody(idempotencyKey, amountMinor, "tok_sim_success", "AUTOMATIC");
    }

    private static String createBody(
        String idempotencyKey,
        long amountMinor,
        String token,
        String captureMethod
    ) {
        return """
            {
              "idempotencyKey": "%s",
              "callbackReference": "pi_%s",
              "method": "CARD",
              "token": "%s",
              "amountMinor": %d,
              "currency": "INR",
              "captureMethod": "%s"
            }
            """.formatted(idempotencyKey, UUID.randomUUID(), token, amountMinor, captureMethod);
    }

    private static String providerPaymentIdOf(String responseBody) {
        int start = responseBody.indexOf("\"providerPaymentId\":\"") + 21;

        return responseBody.substring(start, responseBody.indexOf('"', start));
    }

    private Integer callbackCountFor(String providerPaymentId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from provider_outbound_callbacks where provider_payment_id = ?",
            Integer.class,
            providerPaymentId
        );
    }

    private Integer distinctEventIdsFor(String providerPaymentId) {
        return jdbcTemplate.queryForObject(
            "select count(distinct external_event_id) from provider_outbound_callbacks "
                + "where provider_payment_id = ?",
            Integer.class,
            providerPaymentId
        );
    }
}
