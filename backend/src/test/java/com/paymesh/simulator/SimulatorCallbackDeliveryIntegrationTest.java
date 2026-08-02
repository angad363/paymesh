package com.paymesh.simulator;

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
import com.paymesh.simulator.application.CallbackSender;
import com.paymesh.simulator.application.CreateSimulatedPaymentCommand;
import com.paymesh.simulator.application.CreateSimulatedPaymentService;
import com.paymesh.simulator.application.DispatchProviderCallbacksService;
import com.paymesh.simulator.application.OutboundCallbackRepository;
import com.paymesh.simulator.domain.SimulatedCaptureMethod;
import com.paymesh.simulator.domain.SimulatedMethod;
import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.infrastructure.http.HttpCallbackSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE TEST THIS MODULE EXISTS TO MAKE POSSIBLE.
 *
 * <h2>Why it runs on a real port</h2>
 *
 * Every other test here calls a service or a MockMvc handler. This one starts a real server and lets
 * the simulator's dispatcher POST to it over a socket, so the bytes actually cross the boundary and
 * go through {@code ProviderCallbackSignatureFilter} rather than around it. That filter is the only
 * authentication on the route that marks a payment SUCCEEDED; a simulator whose signature was
 * verified by a test double would be a simulator nobody had checked could talk to PayMesh at all.
 * <p>
 * It also makes the contract duplication self-policing. {@code CallbackBody} restates
 * {@code ProviderCallbackRequest} rather than importing it (SDD 13.6, {@code ModuleBoundaryTest}),
 * and the cost of publishing a contract instead of sharing a type is that the two can drift. This is
 * the test that goes red when they do -- which is exactly the notification a shared type would have
 * suppressed.
 *
 * <h2>The dispatcher is constructed here, not injected</h2>
 *
 * The bean wired by {@code SimulatorConfiguration} points at {@code paymesh.simulator.callback-url},
 * which names port 8080; this server is on a random one. So the production
 * {@code DispatchProviderCallbacksService} is rebuilt with exactly one collaborator swapped -- an
 * {@code HttpCallbackSender} aimed at the live port. Everything else, including the signing, is the
 * production object.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SimulatorCallbackDeliveryIntegrationTest {

    private static final String DEV_SECRET = "dev-only-insecure-provider-callback-secret-change-me";
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30Z");
    private static final long AMOUNT_MINOR = 1999;

    @LocalServerPort
    private int port;

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
    private CreateSimulatedPaymentService createSimulatedPaymentService;

    @Autowired
    private OutboundCallbackRepository outboundCallbacks;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The whole loop: PayMesh confirms an intent into PROCESSING, the simulator takes the payment and
     * queues a callback, the dispatcher signs and POSTs it, the real filter verifies it, and the
     * intent reaches SUCCEEDED. Nothing in this path is stubbed.
     */
    @Test
    void drivesAPaymentIntentToSucceededOverRealHttp() {
        String intentId = processingIntent();

        createSimulatedPayment(intentId, "tok_sim_success", SimulatedCaptureMethod.AUTOMATIC);

        DispatchProviderCallbacksService.DispatchResult result = dispatcher().dispatch();

        assertThat(result.delivered()).isEqualTo(1);
        assertThat(statusOf(intentId)).isEqualTo("SUCCEEDED");
        assertThat(lastOutcomeFor(intentId)).isEqualTo("APPLIED");
        assertThat(callbackStatusFor(intentId)).isEqualTo("DELIVERED");
    }

    /** A declined token drives the same loop to the opposite terminal state. */
    @Test
    void drivesAPaymentIntentToFailedWhenTheIssuerDeclines() {
        String intentId = processingIntent();

        createSimulatedPayment(intentId, "tok_sim_decline", SimulatedCaptureMethod.AUTOMATIC);

        assertThat(dispatcher().dispatch().delivered()).isEqualTo(1);
        assertThat(statusOf(intentId)).isEqualTo("FAILED");
        assertThat(lastOutcomeFor(intentId)).isEqualTo("APPLIED");
    }

    /**
     * MANUAL stops at AUTHORIZED and does NOT collect. ADR-012 section 4 refuses AUTHORIZED to
     * SUCCEEDED as a provider transition, so a simulator that captured on its own say-so would
     * produce an IGNORED_TERMINAL here rather than an authorized intent.
     */
    @Test
    void leavesAnIntentAuthorizedWhenTheProviderWaitsToBeAsked() {
        String intentId = processingIntent();

        createSimulatedPayment(intentId, "tok_sim_success", SimulatedCaptureMethod.MANUAL);

        assertThat(dispatcher().dispatch().delivered()).isEqualTo(1);
        assertThat(statusOf(intentId)).isEqualTo("AUTHORIZED");
    }

    /**
     * THE DUPLICATE. Two rows, one event id, one body: PayMesh answers APPLIED then DUPLICATE and the
     * payment is applied once. The dedup is {@code pk_provider_callbacks}, and this proves it holds
     * against a real second delivery rather than a simulated one.
     */
    @Test
    void appliesADuplicatedCallbackExactlyOnce() {
        String intentId = processingIntent();

        createSimulatedPayment(intentId, "tok_sim_duplicate", SimulatedCaptureMethod.AUTOMATIC);

        assertThat(dispatcher().dispatch().delivered()).isEqualTo(2);
        assertThat(statusOf(intentId)).isEqualTo("SUCCEEDED");
        assertThat(outcomesFor(intentId)).containsExactlyInAnyOrder("APPLIED", "DUPLICATE");
        assertThat(storedCallbackCount(intentId))
            .as("one row per event id; the duplicate did not create a second")
            .isEqualTo(1);
    }

    /**
     * THE OUT-OF-ORDER PAIR. Distinct event ids, the second stamped earlier. PayMesh judges staleness
     * before the state machine, so the refusal is IGNORED_STALE and not IGNORED_TERMINAL -- which is
     * what makes this reproducible from outside with no merchant action in between.
     */
    @Test
    void refusesACallbackThatArrivesOutOfOrder() {
        String intentId = processingIntent();

        createSimulatedPayment(intentId, "tok_sim_stale", SimulatedCaptureMethod.AUTOMATIC);

        assertThat(dispatcher().dispatch().delivered()).isEqualTo(2);
        assertThat(statusOf(intentId)).isEqualTo("SUCCEEDED");
        assertThat(outcomesFor(intentId)).containsExactlyInAnyOrder("APPLIED", "IGNORED_STALE");
    }

    /**
     * THE LOST CALLBACK, and the reason ADR-015's sweeper exists. The provider decided and never
     * said so, and the intent is left in PROCESSING with nothing coming. Until this module existed
     * that state could only be reached by hand.
     */
    @Test
    void strandsAnIntentInProcessingWhenTheProviderNeverReports() {
        String intentId = processingIntent();

        createSimulatedPayment(intentId, "tok_sim_timeout", SimulatedCaptureMethod.AUTOMATIC);

        assertThat(dispatcher().dispatch().delivered())
            .as("TIMEOUT enqueues no row at all, so there is nothing to deliver")
            .isZero();
        assertThat(statusOf(intentId)).isEqualTo("PROCESSING");
    }

    /**
     * A 404 is retried rather than abandoned, because ADR-012 section 7 says it most likely means the
     * callback overtook the transaction that created the intent it names. The row stays PENDING and
     * its attempt count goes up.
     */
    @Test
    void retriesRatherThanAbandoningWhenPayMeshDoesNotKnowTheIntent() {
        SimulatedPayment payment = createSimulatedPayment(
            "pi_" + UUID.randomUUID(), "tok_sim_success", SimulatedCaptureMethod.AUTOMATIC
        );

        DispatchProviderCallbacksService.DispatchResult result = dispatcher().dispatch();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.delivered()).isZero();

        Map<String, Object> row = jdbc.queryForMap(
            "select status, attempts, last_response_status from provider_outbound_callbacks "
                + "where provider_payment_id = ?",
            payment.providerPaymentId().value()
        );

        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_response_status")).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The production service with one collaborator swapped: a sender aimed at this test's port. The
     * signing, the body handling and the transaction boundaries are all the production objects.
     */
    private DispatchProviderCallbacksService dispatcher() {
        CallbackSender sender = new HttpCallbackSender(
            RestClient.builder().build(),
            "http://localhost:" + port + "/internal/v1/provider-callbacks/SIMULATOR",
            DEV_SECRET,
            objectMapper,
            clock
        );

        return new DispatchProviderCallbacksService(
            outboundCallbacks, sender, transactions, clock, 20, 5, Duration.ofSeconds(5)
        );
    }

    private SimulatedPayment createSimulatedPayment(
        String callbackReference,
        String token,
        SimulatedCaptureMethod captureMethod
    ) {
        return createSimulatedPaymentService.create(new CreateSimulatedPaymentCommand(
            "idem-" + UUID.randomUUID(),
            callbackReference,
            SimulatedMethod.CARD,
            token,
            AMOUNT_MINOR,
            "INR",
            captureMethod
        )).payment();
    }

    private String processingIntent() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Simulator Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();

        String orderId = orders.save(Order.create(
            OrderId.generate(), merchantId, null, null, AMOUNT_MINOR, "INR", null,
            Map.of(), null, CREATED_AT
        )).orderId().value();

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, orderId, null, AMOUNT_MINOR, "INR", null, null, Map.of()
        ));

        attachPaymentMethodService.attach(merchantId, intent.paymentIntentId(), PaymentMethodType.CARD);
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        return intent.paymentIntentId().value();
    }

    private String statusOf(String paymentIntentId) {
        return jdbc.queryForObject(
            "select status from payment_intents where payment_intent_id = ?",
            String.class, paymentIntentId
        );
    }

    private String lastOutcomeFor(String paymentIntentId) {
        return jdbc.queryForObject(
            "select last_response_outcome from provider_outbound_callbacks "
                + "where callback_reference = ? order by created_at limit 1",
            String.class, paymentIntentId
        );
    }

    private String callbackStatusFor(String paymentIntentId) {
        return jdbc.queryForObject(
            "select status from provider_outbound_callbacks where callback_reference = ? limit 1",
            String.class, paymentIntentId
        );
    }

    private java.util.List<String> outcomesFor(String paymentIntentId) {
        return jdbc.queryForList(
            "select last_response_outcome from provider_outbound_callbacks "
                + "where callback_reference = ?",
            String.class, paymentIntentId
        );
    }

    /** PayMesh's own inbound dedup table, not the simulator's outbound one. */
    private Integer storedCallbackCount(String paymentIntentId) {
        return jdbc.queryForObject(
            "select count(*) from provider_callbacks where payment_intent_id = ?",
            Integer.class, paymentIntentId
        );
    }
}
