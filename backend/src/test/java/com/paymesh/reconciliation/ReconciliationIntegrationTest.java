package com.paymesh.reconciliation;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE JOB ADR-015 NAMED, PROVED END TO END AGAINST A REAL POSTGRESQL (ADR-026), ADAPTED FOR
 * EXTRACTION (ADR-041).
 *
 * <h2>What is actually being demonstrated</h2>
 *
 * The scenario is the one that costs a merchant real money:
 * <ol>
 *   <li>PayMesh confirms an intent into PROCESSING.</li>
 *   <li>The provider takes the payment and COLLECTS IT. Its callback is never delivered.</li>
 *   <li>ADR-015's sweeper does what it was built to do and times the intent out to FAILED --
 *       <b>with no evidence the payment failed</b>, which its own javadoc admits.</li>
 *   <li>Reconciliation reads the provider's own daily record, sees CAPTURED, and repairs it.</li>
 * </ol>
 *
 * <h2>What changed at extraction, and why the seam moved rather than the coverage</h2>
 *
 * Before the provider simulator left this process (ADR-041), step 4's "provider's own daily
 * record" was fetched over a real loopback HTTP call to the SAME running application, because the
 * simulator was a module of it. This module can no longer compile
 * {@code com.paymesh.simulator}'s classes at all, so there is no in-process way left to manufacture
 * that record. The fetch was ALWAYS an HTTP call through {@link ProviderReconciliationSource} --
 * {@code ModuleBoundaryTest} forbade the alternative from the start, specifically so a real
 * acquirer's file needs only a new adapter, never a rewrite of this job. So the day's report is now
 * a hand-built JSON document served by a WireMock stub, in exactly the shape
 * {@link HttpProviderReconciliationSource} parses -- the same pattern ADR-019 already uses for
 * refund callbacks ("hand-signed HMAC in tests"), now applied to the provider's report instead of
 * its callback.
 * <p>
 * What this test is actually responsible for is unchanged and untouched by the move:
 * {@link ReconcileProviderDayService}, {@link PaymentModuleRepair} and the real
 * {@code RecordProviderCallbackService}/Ledger consumer chain they drive are the exact production
 * objects, exercised against a real PostgreSQL. Only the shape of "what the provider says" moved
 * from a live simulator response to a stubbed one -- the same substitution the now-separate
 * {@code provider-sim} module makes in the other direction for its own delivery test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ReconciliationIntegrationTest {

    private static final String DEV_API_KEY = "dev-only-insecure-simulator-api-key-change-me";
    private static final String API_KEY_HEADER = "X-PayMesh-Simulator-Key";
    private static final String PROVIDER = "SIMULATOR";
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30Z");
    private static final long AMOUNT_MINOR = 1999;

    private static final WireMockServer PROVIDER_STUB = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void startStub() {
        PROVIDER_STUB.start();
    }

    @AfterAll
    static void stopStub() {
        PROVIDER_STUB.stop();
    }

    @AfterEach
    void resetStub() {
        PROVIDER_STUB.resetAll();
    }

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

        // ADR-015's sweeper gives up on it. The guess is wrong, and nothing knows.
        strand(intentId);
        timeOutProcessingPayments.sweep();
        assertThat(statusOf(intentId))
            .as("the sweeper failed a payment the provider had already collected")
            .isEqualTo("FAILED");

        stubCapturedPayment(today(), intentId, AMOUNT_MINOR);

        ReconciliationResult result = reconciliation().reconcile(today());

        assertThat(result.repaired()).isEqualTo(1);
        assertThat(statusOf(intentId)).isEqualTo("SUCCEEDED");
    }

    /**
     * THE REPAIR MUST REACH THE LEDGER, or it has corrected a status column and left the money wrong.
     */
    @Test
    void postsTheRepairedPaymentToTheLedger() {
        String intentId = processingIntent();
        strand(intentId);
        timeOutProcessingPayments.sweep();
        stubCapturedPayment(today(), intentId, AMOUNT_MINOR);

        reconciliation().reconcile(today());
        drain();

        assertThat(ledgerEntryCountFor(intentId))
            .as("a repaired payment must move the balance, not just the status column")
            .isPositive();
    }

    /**
     * RE-RUNNING A DAY MUST NOT APPLY ANYTHING TWICE.
     * <p>
     * <b>Sabotage that must turn this red:</b> put a UUID or the clock into the minted event id. The
     * second run then reports a second repair and inserts a second callback row.
     */
    @Test
    void reconcilingTheSameDayTwiceRepairsNothingTheSecondTime() {
        String intentId = processingIntent();
        strand(intentId);
        timeOutProcessingPayments.sweep();
        stubCapturedPayment(today(), intentId, AMOUNT_MINOR);

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
     */
    @Test
    void replacesTheSweepersGuessWithTheProvidersConfirmationAndClosesTheIntent() {
        String intentId = processingIntent();
        strand(intentId);
        timeOutProcessingPayments.sweep();
        assertThat(statusOf(intentId)).isEqualTo("FAILED");
        assertThat(failureCodeOf(intentId))
            .as("the sweeper's own code: nobody answered, and this MAY still have succeeded")
            .isEqualTo("provider_no_response");

        stubTimedOutPayment(today(), intentId);

        ReconciliationResult result = reconciliation().reconcile(today());

        assertThat(result.examined()).isPositive();
        assertThat(statusOf(intentId))
            .as("the provider agrees it collected nothing, so the status does not move")
            .isEqualTo("FAILED");
        assertThat(failureCodeOf(intentId))
            .as("but the guess is now a confirmation, which is a different fact")
            .isEqualTo("provider_reported_no_collection");

        assertThat(reconciliation().reconcile(today()).repaired())
            .as("re-running finds a payment that is settled rather than merely given up on")
            .isZero();
    }

    /**
     * A provider row naming a payment PayMesh never created is REPORTED, never invented.
     */
    @Test
    void reportsAProviderPaymentPayMeshHasNoRecordOf() {
        stubCapturedPayment(today(), "pi_" + UUID.randomUUID(), AMOUNT_MINOR);

        ReconciliationResult result = reconciliation().reconcile(today());

        assertThat(result.unresolved()).isEqualTo(1);
        assertThat(result.repaired()).isZero();
    }

    /**
     * AN UNREACHABLE PROVIDER MUST NOT LOOK LIKE A CLEAN DAY.
     */
    @Test
    void refusesToReportACleanDayWhenTheProviderCannotBeReached() {
        ReconcileProviderDayService unreachable = reconciliationAgainst("http://localhost:1");

        assertThatThrownBy(() -> unreachable.reconcile(today()))
            .isInstanceOf(ProviderReportUnavailableException.class);
    }

    /**
     * THE API KEY IS REAL AUTHENTICATION AND THIS PROVES IT IS BEING SENT. No stub matches a request
     * missing the correct key, so it falls through to WireMock's unmatched-request 404 -- which the
     * adapter turns into "the provider could not be read", correctly and loudly, rather than an
     * empty file.
     */
    @Test
    void cannotReadTheReportWithoutTheProvidersApiKey() {
        LocalDate day = today();
        PROVIDER_STUB.stubFor(get(urlEqualTo("/sim/v1/reconciliation/" + day))
            .withHeader(API_KEY_HEADER, equalTo(DEV_API_KEY))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"payments\":[],\"refunds\":[]}")));

        ProviderReconciliationSource unauthenticated = new HttpProviderReconciliationSource(
            RestClient.builder().baseUrl(baseUrl()).build(), API_KEY_HEADER, "the-wrong-key"
        );

        assertThatThrownBy(() -> unauthenticated.fetch(day))
            .isInstanceOf(ProviderReportUnavailableException.class);
    }

    // ------------------------------------------------------------------ helpers

    /** The production objects, with one collaborator aimed at the WireMock stub. */
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
        return "http://localhost:" + PROVIDER_STUB.port();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** One CAPTURED row, in the exact shape {@code HttpProviderReconciliationSource} parses. */
    private void stubCapturedPayment(LocalDate day, String callbackReference, long capturedAmountMinor) {
        stubReport(day, """
            {"payments":[{
              "providerPaymentId":"sim_pay_%s",
              "callbackReference":"%s",
              "status":"CAPTURED",
              "amountMinor":%d,
              "capturedAmountMinor":%d,
              "failureCode":null,
              "failureMessage":null,
              "updatedAt":"%s"
            }],"refunds":[]}
            """.formatted(
                UUID.randomUUID(), callbackReference, capturedAmountMinor, capturedAmountMinor,
                clock.instant()
            ));
    }

    /** A row the provider decided and never reported -- capturedAmountMinor is zero, per ADR-026. */
    private void stubTimedOutPayment(LocalDate day, String callbackReference) {
        stubReport(day, """
            {"payments":[{
              "providerPaymentId":"sim_pay_%s",
              "callbackReference":"%s",
              "status":"TIMED_OUT",
              "amountMinor":%d,
              "capturedAmountMinor":0,
              "failureCode":null,
              "failureMessage":null,
              "updatedAt":"%s"
            }],"refunds":[]}
            """.formatted(UUID.randomUUID(), callbackReference, AMOUNT_MINOR, clock.instant()));
    }

    private void stubReport(LocalDate day, String body) {
        PROVIDER_STUB.stubFor(get(urlEqualTo("/sim/v1/reconciliation/" + day))
            .withHeader(API_KEY_HEADER, equalTo(DEV_API_KEY))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
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
     * with its production configuration, decide it has waited long enough.
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
