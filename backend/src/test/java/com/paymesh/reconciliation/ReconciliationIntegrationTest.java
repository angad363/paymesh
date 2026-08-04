package com.paymesh.reconciliation;

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
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.application.TimeOutProcessingPaymentsService;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.reconciliation.application.PaymentRepair;
import com.paymesh.reconciliation.application.ProviderReconciliationSource;
import com.paymesh.reconciliation.application.ProviderReportUnavailableException;
import com.paymesh.reconciliation.application.ReconcileProviderDayService;
import com.paymesh.reconciliation.application.ReconcileProviderDayService.ReconciliationResult;
import com.paymesh.reconciliation.application.RefundRepair;
import com.paymesh.reconciliation.infrastructure.http.HttpProviderReconciliationSource;
import com.paymesh.reconciliation.infrastructure.payment.PaymentModuleRepair;
import com.paymesh.reconciliation.infrastructure.refund.RefundModuleRepair;
import com.paymesh.refund.application.RecordRefundCallbackService;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.simulator.application.CreateSimulatedPaymentCommand;
import com.paymesh.simulator.application.CreateSimulatedPaymentService;
import com.paymesh.simulator.domain.SimulatedCaptureMethod;
import com.paymesh.simulator.domain.SimulatedMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE JOB ADR-015 NAMED, PROVED END TO END AGAINST A REAL PROVIDER AND A REAL POSTGRESQL (ADR-026).
 *
 * <h2>What is actually being demonstrated</h2>
 *
 * The scenario is the one that costs a merchant real money, and every step of it is reachable from
 * outside for the first time:
 * <ol>
 *   <li>PayMesh confirms an intent into PROCESSING.</li>
 *   <li>The provider takes the payment and COLLECTS IT. It queues a callback.</li>
 *   <li><b>The callback is never delivered.</b> The dispatcher is off under the {@code dev} profile
 *       this suite runs on, which is exactly what a lost callback looks like from PayMesh's side.</li>
 *   <li>ADR-015's sweeper does what it was built to do and times the intent out to FAILED --
 *       <b>with no evidence the payment failed</b>, which its own javadoc admits.</li>
 *   <li>Reconciliation reads the provider's own daily record, sees CAPTURED, and repairs it.</li>
 * </ol>
 * Between steps 4 and 5 PayMesh believes a collected payment failed. That is not an untidy row: the
 * Ledger never posts, so the merchant's balance is short by the amount, permanently, and nothing
 * anywhere reports it.
 *
 * <h2>It runs on a real port, and that is the point of the design</h2>
 *
 * {@code ModuleBoundaryTest} forbids any capability from importing the simulator, so the fetch is a
 * real HTTP GET against a real server -- through {@code SimulatorApiKeyFilter}, through Jackson,
 * through the same adapter production uses. A test that called {@code ExportReconciliationService}
 * directly would prove the job works against the one "provider" that can never be a real one.
 * <p>
 * The service is CONSTRUCTED here rather than injected, for the same reason
 * {@code SimulatorCallbackDeliveryIntegrationTest} rebuilds its dispatcher: the wired bean points at
 * {@code paymesh.reconciliation.base-url}, which names port 8080, and this server is on a random
 * one. Exactly one collaborator differs -- a {@code RestClient} aimed at the live port. Both
 * adapters, the HTTP source and the job itself are the production objects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ReconciliationIntegrationTest {

    private static final String DEV_API_KEY = "dev-only-insecure-simulator-api-key-change-me";
    private static final String API_KEY_HEADER = "X-PayMesh-Simulator-Key";
    private static final String PROVIDER = "SIMULATOR";
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
    private RecordProviderCallbackService paymentCallbacks;

    @Autowired
    private RecordRefundCallbackService refundCallbacks;

    @Autowired
    private TimeOutProcessingPaymentsService timeOutProcessingPayments;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * THE HEADLINE. A collected payment that PayMesh gave up on is put right from the provider's own
     * record.
     * <p>
     * <b>Sabotage that must turn this red:</b> have {@code ReconcileProviderDayService} skip rows
     * whose PayMesh state is already terminal -- the "don't touch settled payments" instinct. FAILED
     * is terminal, so the repair would never be attempted and the merchant would stay short.
     */
    @Test
    void repairsAPaymentPayMeshTimedOutThatTheProviderActuallyCollected() {
        String intentId = processingIntent();
        createSimulatedPayment(intentId, "tok_sim_success");

        // The callback is queued and never dispatched. This is the lost callback.
        assertThat(statusOf(intentId)).isEqualTo("PROCESSING");

        // ADR-015's sweeper gives up on it. The guess is wrong, and nothing knows.
        strand(intentId);
        timeOutProcessingPayments.sweep();
        assertThat(statusOf(intentId))
            .as("the sweeper failed a payment the provider had already collected")
            .isEqualTo("FAILED");

        ReconciliationResult result = reconciliation().reconcile(today());

        assertThat(result.repaired()).isEqualTo(1);
        assertThat(statusOf(intentId)).isEqualTo("SUCCEEDED");
    }

    /**
     * THE REPAIR MUST REACH THE LEDGER, or it has corrected a status column and left the money wrong.
     * <p>
     * Nothing here calls the Ledger. The repair goes through the ordinary callback service, which
     * writes {@code payment.succeeded} to the outbox in the same transaction, and the Ledger's
     * consumer posts a balanced journal when the relay delivers it. That the reused entry point
     * carries all of this for free is the entire argument for replaying rather than diffing.
     */
    @Test
    void postsTheRepairedPaymentToTheLedger() {
        String intentId = processingIntent();
        createSimulatedPayment(intentId, "tok_sim_success");
        strand(intentId);
        timeOutProcessingPayments.sweep();

        reconciliation().reconcile(today());
        drain();

        assertThat(ledgerEntryCountFor(intentId))
            .as("a repaired payment must move the balance, not just the status column")
            .isPositive();
    }

    /**
     * RE-RUNNING A DAY MUST NOT APPLY ANYTHING TWICE. The schedule reconciles the same recent days
     * on every pass and an operator will re-run one by hand; without the deterministic event id each
     * run would look like a new provider event. On the capture path that means collecting twice.
     * <p>
     * <b>Sabotage that must turn this red:</b> put a UUID or the clock into the minted event id. The
     * second run then reports a second repair and inserts a second callback row.
     */
    @Test
    void reconcilingTheSameDayTwiceRepairsNothingTheSecondTime() {
        String intentId = processingIntent();
        createSimulatedPayment(intentId, "tok_sim_success");
        strand(intentId);
        timeOutProcessingPayments.sweep();

        ReconcileProviderDayService reconciliation = reconciliation();

        assertThat(reconciliation.reconcile(today()).repaired()).isEqualTo(1);
        assertThat(reconciliation.reconcile(today()).repaired())
            .as("the second run is a duplicate, not a second application")
            .isZero();

        assertThat(storedCallbackCount(intentId))
            .as("one reconciliation event id, one row in PayMesh's inbound dedup table")
            .isEqualTo(1);
    }

    /**
     * A CONFIRMED FAILURE IS NOT THE SAME ROW AS A GUESSED ONE, AND THIS IS WHERE THE GUESS CLOSES.
     * <p>
     * The provider timed out: it collected nothing and said nothing. PayMesh's sweeper reached the
     * same conclusion, so the status does not move -- FAILED before, FAILED after. What DOES move is
     * the reason, and it is the more important half. {@code provider_no_response} means "we gave up
     * waiting and this may still have succeeded"; {@code provider_reported_no_collection} means "the
     * provider's own record says nothing was collected". Only the second is a settled fact.
     * <p>
     * <b>That also closes the intent to further revision, which is the safety property.</b>
     * ADR-026 lets a provider outcome speak into FAILED only while the failure code is the sweeper's
     * guess ({@code PaymentIntent.isUnansweredTimeout}). Overwriting the code with a confirmation is
     * what takes the payment back out of that window -- so a payment stays revisable exactly as long
     * as it is genuinely unresolved, and no longer.
     */
    @Test
    void replacesTheSweepersGuessWithTheProvidersConfirmationAndClosesTheIntent() {
        String intentId = processingIntent();
        createSimulatedPayment(intentId, "tok_sim_timeout");

        strand(intentId);
        timeOutProcessingPayments.sweep();
        assertThat(statusOf(intentId)).isEqualTo("FAILED");
        assertThat(failureCodeOf(intentId))
            .as("the sweeper's own code: nobody answered, and this MAY still have succeeded")
            .isEqualTo("provider_no_response");

        ReconciliationResult result = reconciliation().reconcile(today());

        assertThat(result.examined()).isPositive();
        assertThat(statusOf(intentId))
            .as("the provider agrees it collected nothing, so the status does not move")
            .isEqualTo("FAILED");
        assertThat(failureCodeOf(intentId))
            .as("but the guess is now a confirmation, which is a different fact")
            .isEqualTo("provider_reported_no_collection");

        // And now it is genuinely terminal. A late outcome after this point is absorbed, because the
        // failure is no longer the sweeper's guess.
        assertThat(reconciliation().reconcile(today()).repaired())
            .as("re-running finds a payment that is settled rather than merely given up on")
            .isZero();
    }

    /**
     * A provider row naming a payment PayMesh never created is REPORTED, never invented. Conjuring a
     * local intent from a provider's file would be manufacturing money movement out of a document.
     */
    @Test
    void reportsAProviderPaymentPayMeshHasNoRecordOf() {
        createSimulatedPayment("pi_" + UUID.randomUUID(), "tok_sim_success");

        ReconciliationResult result = reconciliation().reconcile(today());

        assertThat(result.unresolved()).isEqualTo(1);
        assertThat(result.repaired()).isZero();
    }

    /**
     * AN UNREACHABLE PROVIDER MUST NOT LOOK LIKE A CLEAN DAY. Pointed at a port nothing is listening
     * on, the adapter must raise rather than return an empty report -- zero examined and zero
     * repaired is precisely what a quiet day looks like, and a provider that has been down for a
     * week would otherwise report success every night.
     */
    @Test
    void refusesToReportACleanDayWhenTheProviderCannotBeReached() {
        ReconcileProviderDayService unreachable = reconciliationAgainst("http://localhost:1");

        assertThatThrownBy(() -> unreachable.reconcile(today()))
            .isInstanceOf(ProviderReportUnavailableException.class);
    }

    /**
     * THE API KEY IS REAL AUTHENTICATION AND THIS PROVES IT IS BEING SENT. Without the header
     * {@code SimulatorApiKeyFilter} rejects the request, which the adapter turns into "the provider
     * could not be read" -- correctly, and loudly, rather than into an empty file.
     */
    @Test
    void cannotReadTheReportWithoutTheProvidersApiKey() {
        ProviderReconciliationSource unauthenticated = new HttpProviderReconciliationSource(
            RestClient.builder().baseUrl(baseUrl()).build(), API_KEY_HEADER, "the-wrong-key"
        );

        assertThatThrownBy(() -> unauthenticated.fetch(today()))
            .isInstanceOf(ProviderReportUnavailableException.class);
    }

    // ------------------------------------------------------------------ helpers

    /** The production objects, with one collaborator aimed at this test's port. */
    private ReconcileProviderDayService reconciliation() {
        return reconciliationAgainst(baseUrl());
    }

    private ReconcileProviderDayService reconciliationAgainst(String baseUrl) {
        ProviderReconciliationSource source = new HttpProviderReconciliationSource(
            RestClient.builder().baseUrl(baseUrl).build(), API_KEY_HEADER, DEV_API_KEY
        );

        PaymentRepair paymentRepair = new PaymentModuleRepair(paymentCallbacks, PROVIDER);
        RefundRepair refundRepair = new RefundModuleRepair(refundCallbacks, PROVIDER);

        return new ReconcileProviderDayService(source, paymentRepair, refundRepair, 1, clock);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** The export is keyed on the provider's own created_at, so the day is the application's day. */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Takes the payment at the provider and then LOSES the callback it queued.
     * <p>
     * Deleting the outbound row is not cleanup, it is the scenario: a lost callback is one that never
     * arrives, and leaving it queued would model a merely delayed one. The provider's own
     * {@code provider_payments} record -- which is what the reconciliation export reads -- is
     * untouched, which is exactly the asymmetry this job exists to exploit.
     * <p>
     * It also keeps this test from polluting {@code SimulatorCallbackDeliveryIntegrationTest}, which
     * shares the container and asserts on how many callbacks one dispatch pass delivered. Rows left
     * pending here would be counted there.
     */
    private void createSimulatedPayment(String callbackReference, String token) {
        var payment = createSimulatedPaymentService.create(new CreateSimulatedPaymentCommand(
            "idem-" + UUID.randomUUID(),
            callbackReference,
            SimulatedMethod.CARD,
            token,
            AMOUNT_MINOR,
            "INR",
            SimulatedCaptureMethod.AUTOMATIC
        )).payment();

        jdbc.update(
            "delete from provider_outbound_callbacks where provider_payment_id = ?",
            payment.providerPaymentId().value()
        );
    }

    private String processingIntent() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Reconciliation Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();

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

    /** More than one pass: a consumer appends its own event inside the transaction the pass claimed. */
    private void drain() {
        while (relay.publish().published() > 0) {
            // Keep going until a pass delivers nothing.
        }
    }

    /**
     * Back-dates the intent so ADR-015's sweeper considers it stranded.
     * <p>
     * The sweep's predicate is {@code status = 'PROCESSING' and updated_at <= cutoff}, and the cutoff
     * is an hour by configuration. Rather than reconstruct the service with a one-millisecond age --
     * which would test a sweeper nobody runs -- this ages the ROW and lets the PRODUCTION sweeper,
     * with its production configuration, decide it has waited long enough. A week clears any cutoff
     * anyone would plausibly configure.
     */
    private void strand(String paymentIntentId) {
        jdbc.update(
            "update payment_intents set updated_at = updated_at - interval '7 days' "
                + "where payment_intent_id = ?",
            paymentIntentId
        );
    }

    private String failureCodeOf(String paymentIntentId) {
        return jdbc.queryForObject(
            "select failure_code from payment_intents where payment_intent_id = ?",
            String.class, paymentIntentId
        );
    }

    private String statusOf(String paymentIntentId) {
        return jdbc.queryForObject(
            "select status from payment_intents where payment_intent_id = ?",
            String.class, paymentIntentId
        );
    }

    private Integer storedCallbackCount(String paymentIntentId) {
        return jdbc.queryForObject(
            "select count(*) from provider_callbacks where payment_intent_id = ?",
            Integer.class, paymentIntentId
        );
    }

    private Integer ledgerEntryCountFor(String paymentIntentId) {
        return jdbc.queryForObject(
            """
            select count(*)
              from ledger_entries e
              join ledger_transactions t on t.ledger_transaction_id = e.ledger_transaction_id
             where t.reference_id = ?
            """,
            Integer.class, paymentIntentId
        );
    }
}
