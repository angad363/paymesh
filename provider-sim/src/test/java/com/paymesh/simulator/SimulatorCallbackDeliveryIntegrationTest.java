package com.paymesh.simulator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.paymesh.TestcontainersConfiguration;
import com.paymesh.simulator.application.CallbackSender;
import com.paymesh.simulator.application.CreateSimulatedPaymentCommand;
import com.paymesh.simulator.application.CreateSimulatedPaymentService;
import com.paymesh.simulator.application.DispatchProviderCallbacksService;
import com.paymesh.simulator.application.OutboundCallbackRepository;
import com.paymesh.simulator.domain.SimulatedCaptureMethod;
import com.paymesh.simulator.domain.SimulatedMethod;
import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.infrastructure.http.HttpCallbackSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE TEST THIS MODULE EXISTS TO MAKE POSSIBLE, ADAPTED FOR EXTRACTION (ADR-041).
 *
 * <h2>What changed and why</h2>
 *
 * Before this module left the process, this test drove a real merchant, order and payment intent
 * through the monolith's own services and asserted the intent reached SUCCEEDED -- proving the
 * simulator's dispatcher could talk to a REAL {@code ProviderCallbackSignatureFilter} and a real
 * state machine. Extracted, this module can no longer compile Payment's or Order's classes at all,
 * so that receiver is now a WireMock stub, exactly as the gateway's own tests stand WireMock in for
 * the monolith (ADR-040).
 * <p>
 * What survives unchanged is everything THIS module is responsible for: the bytes actually go out
 * over a socket, signed for real, and the four ADR-012 ordering/dedup scenarios are proven against
 * real HTTP responses rather than a mocked {@link CallbackSender}. What moved to the OTHER side of
 * the boundary -- "does a signed callback actually flip a payment intent to SUCCEEDED" -- is now
 * {@code ProviderCallbackApiTest} and {@code ProviderCallbackIntegrationTest} in the monolith's own
 * suite, which already hand-sign a callback body rather than depend on this module (the same pattern
 * ADR-019 established for Refund). Contract drift between {@link com.paymesh.simulator.domain.CallbackBody}
 * and the monolith's {@code ProviderCallbackRequest} is still self-policing: this test hand-verifies
 * the signature this module produces against the scheme {@code ProviderCallbackSignatureFilter}
 * checks (documented in {@link HttpCallbackSender}), so a drift in the signing bytes goes red here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SimulatorCallbackDeliveryIntegrationTest {

    private static final String DEV_SECRET = "dev-only-insecure-provider-callback-secret-change-me";
    private static final String CALLBACK_PATH = "/internal/v1/provider-callbacks/SIMULATOR";
    private static final String SIGNATURE_HEADER = "X-PayMesh-Signature";
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("t=(\\d+),v1=([0-9a-f]{64})");
    private static final long AMOUNT_MINOR = 1999;

    private static final WireMockServer PAYMESH = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void startStub() {
        PAYMESH.start();
    }

    @AfterAll
    static void stopStub() {
        PAYMESH.stop();
    }

    @AfterEach
    void resetStub() {
        PAYMESH.resetAll();
    }

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

    /** The whole loop this module owns: create, sign, POST, and record what the receiver answered. */
    @Test
    void deliversACallbackSignedCorrectlyAndRecordsTheAppliedOutcome() {
        String reference = "pi_" + UUID.randomUUID();

        PAYMESH.stubFor(post(urlEqualTo(CALLBACK_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"outcome\":\"APPLIED\"}")));

        SimulatedPayment payment = createSimulatedPayment(reference, "tok_sim_success");

        DispatchProviderCallbacksService.DispatchResult result = dispatcher().dispatch();

        assertThat(result.delivered()).isEqualTo(1);
        assertThat(callbackStatusFor(payment)).isEqualTo("DELIVERED");
        assertThat(lastOutcomeFor(payment)).isEqualTo("APPLIED");

        verifySignatureOfTheOneRequestReceived();
    }

    /**
     * THE DUPLICATE. Two rows, one event id, one body -- {@code SimulatorApiTest} already proves
     * that shape at create time. This proves the DELIVERY half: the same bytes go out twice, and the
     * dispatcher records whatever the receiver says happened to each, APPLIED then DUPLICATE, exactly
     * as a real receiver's dedup would answer.
     */
    @Test
    void appliesADuplicatedCallbackExactlyOnceAccordingToTheReceiver() {
        String reference = "pi_" + UUID.randomUUID();

        PAYMESH.stubFor(post(urlEqualTo(CALLBACK_PATH))
            .inScenario("duplicate")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withBody("{\"outcome\":\"APPLIED\"}"))
            .willSetStateTo("delivered-once"));
        PAYMESH.stubFor(post(urlEqualTo(CALLBACK_PATH))
            .inScenario("duplicate")
            .whenScenarioStateIs("delivered-once")
            .willReturn(aResponse().withStatus(200).withBody("{\"outcome\":\"DUPLICATE\"}")));

        SimulatedPayment payment = createSimulatedPayment(reference, "tok_sim_duplicate");

        assertThat(dispatcher().dispatch().delivered()).isEqualTo(2);
        assertThat(outcomesFor(payment)).containsExactlyInAnyOrder("APPLIED", "DUPLICATE");
        assertThat(distinctEventIdsFor(payment))
            .as("one event id shared by both rows -- this is the duplicate, not two independent events")
            .isEqualTo(1);
    }

    /**
     * THE OUT-OF-ORDER PAIR. Distinct event ids, one stamped earlier. Which one a real receiver
     * would call stale depends on {@code occurred_at}, which this module assigns at create time --
     * so the two stubs are keyed on the actual event ids the create step produced, read back from
     * the row it wrote, rather than guessed.
     */
    @Test
    void deliversBothHalvesOfAnOutOfOrderPairIndependently() {
        String reference = "pi_" + UUID.randomUUID();
        SimulatedPayment payment = createSimulatedPayment(reference, "tok_sim_stale");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "select external_event_id from provider_outbound_callbacks "
                + "where provider_payment_id = ? order by occurred_at",
            payment.providerPaymentId().value()
        );
        assertThat(rows).hasSize(2);
        String earlierEventId = (String) rows.get(0).get("external_event_id");
        String laterEventId = (String) rows.get(1).get("external_event_id");

        stubOutcomeForEventId(earlierEventId, "IGNORED_STALE");
        stubOutcomeForEventId(laterEventId, "APPLIED");

        assertThat(dispatcher().dispatch().delivered()).isEqualTo(2);
        assertThat(outcomesFor(payment)).containsExactlyInAnyOrder("APPLIED", "IGNORED_STALE");
    }

    /**
     * A 404 is retried rather than abandoned (ADR-012 section 7): the row stays PENDING and its
     * attempt count climbs, so the next pass tries again instead of giving up on a callback that
     * most likely overtook the transaction creating the intent it names.
     */
    @Test
    void retriesRatherThanAbandoningOnANotFoundResponse() {
        PAYMESH.stubFor(post(urlEqualTo(CALLBACK_PATH))
            .willReturn(aResponse().withStatus(404)));

        SimulatedPayment payment = createSimulatedPayment("pi_" + UUID.randomUUID(), "tok_sim_success");

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

    /**
     * ONE BAD CALLBACK COSTS ONE CALLBACK, NOT THE PASS. Sabotage that must turn this red: remove the
     * per-item try/catch around {@code deliverOne} in {@code DispatchProviderCallbacksService.dispatch()}.
     */
    @Test
    void deliversTheGoodCallbackEvenWhenTheFirstSendThrows() {
        PAYMESH.stubFor(post(urlEqualTo(CALLBACK_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"outcome\":\"APPLIED\"}")));

        SimulatedPayment poisoned = createSimulatedPayment("pi_" + UUID.randomUUID(), "tok_sim_success");
        SimulatedPayment healthy = createSimulatedPayment("pi_" + UUID.randomUUID(), "tok_sim_success");

        DispatchProviderCallbacksService.DispatchResult result =
            dispatcherWhoseFirstSendThrows().dispatch();

        assertThat(result.errored())
            .as("counted as one failure, not thrown out of the pass")
            .isEqualTo(1);
        assertThat(result.delivered())
            .as("THE POINT: the callback behind the throwing one still went out")
            .isEqualTo(1);
        assertThat(callbackStatusFor(healthy)).isEqualTo("DELIVERED");
        assertThat(callbackStatusFor(poisoned))
            .as("the one that threw rolled back whole -- nothing partial survived")
            .isEqualTo("PENDING");

        dispatcher().dispatch();

        assertThat(callbackStatusFor(poisoned))
            .as("and the next pass delivered it")
            .isEqualTo("DELIVERED");
    }

    // ------------------------------------------------------------------ helpers

    private void stubOutcomeForEventId(String eventId, String outcome) {
        PAYMESH.stubFor(post(urlEqualTo(CALLBACK_PATH))
            .withRequestBody(matchingJsonPath("$.eventId", equalTo(eventId)))
            .willReturn(aResponse().withStatus(200).withBody("{\"outcome\":\"" + outcome + "\"}")));
    }

    /**
     * Recomputes the HMAC {@code ProviderCallbackSignatureFilter} would check -- {@code t + "." +
     * body} under HMAC-SHA256 with the shared secret -- independently of {@link HttpCallbackSender},
     * so a change to the signing bytes there is caught here rather than only agreeing with itself.
     */
    private void verifySignatureOfTheOneRequestReceived() {
        var requests = PAYMESH.findAll(postRequestedFor(urlEqualTo(CALLBACK_PATH)));
        assertThat(requests).hasSize(1);

        String header = requests.get(0).getHeader(SIGNATURE_HEADER);
        String body = requests.get(0).getBodyAsString();

        Matcher matcher = SIGNATURE_PATTERN.matcher(header == null ? "" : header);
        assertThat(matcher.matches()).as("signature header shape: %s", header).isTrue();

        String timestamp = matcher.group(1);
        String expected = hmac(timestamp + "." + body);

        assertThat(matcher.group(2)).isEqualTo(expected);
    }

    private static String hmac(String signedString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DEV_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            return HexFormat.of().formatHex(mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The production dispatcher with a sender that throws exactly once, on the first body it is
     * handed, and behaves normally afterwards -- otherwise the production {@link HttpCallbackSender}
     * aimed at the WireMock stub.
     */
    private DispatchProviderCallbacksService dispatcherWhoseFirstSendThrows() {
        CallbackSender real = sender();
        AtomicBoolean thrown = new AtomicBoolean();

        CallbackSender flaky = (body, target) -> {
            if (thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("the surprise nobody predicted");
            }

            return real.send(body, target);
        };

        return new DispatchProviderCallbacksService(
            outboundCallbacks, flaky, transactions, clock, 20, 5, Duration.ofSeconds(5)
        );
    }

    private DispatchProviderCallbacksService dispatcher() {
        return new DispatchProviderCallbacksService(
            outboundCallbacks, sender(), transactions, clock, 20, 5, Duration.ofSeconds(5)
        );
    }

    /** The production sender, aimed at the WireMock stub instead of a real PayMesh. */
    private CallbackSender sender() {
        return new HttpCallbackSender(
            RestClient.builder().build(),
            "http://localhost:" + PAYMESH.port() + CALLBACK_PATH,
            "http://localhost:" + PAYMESH.port() + "/internal/v1/payout-callbacks/SIMULATOR",
            DEV_SECRET,
            objectMapper,
            clock
        );
    }

    private SimulatedPayment createSimulatedPayment(String callbackReference, String token) {
        return createSimulatedPaymentService.create(new CreateSimulatedPaymentCommand(
            "idem-" + UUID.randomUUID(),
            callbackReference,
            SimulatedMethod.CARD,
            token,
            AMOUNT_MINOR,
            "INR",
            SimulatedCaptureMethod.AUTOMATIC
        )).payment();
    }

    private String callbackStatusFor(SimulatedPayment payment) {
        return jdbc.queryForObject(
            "select status from provider_outbound_callbacks where provider_payment_id = ? "
                + "order by created_at desc limit 1",
            String.class, payment.providerPaymentId().value()
        );
    }

    private String lastOutcomeFor(SimulatedPayment payment) {
        return jdbc.queryForObject(
            "select last_response_outcome from provider_outbound_callbacks "
                + "where provider_payment_id = ? order by created_at limit 1",
            String.class, payment.providerPaymentId().value()
        );
    }

    private List<String> outcomesFor(SimulatedPayment payment) {
        return jdbc.queryForList(
            "select last_response_outcome from provider_outbound_callbacks "
                + "where provider_payment_id = ?",
            String.class, payment.providerPaymentId().value()
        );
    }

    private Integer distinctEventIdsFor(SimulatedPayment payment) {
        return jdbc.queryForObject(
            "select count(distinct external_event_id) from provider_outbound_callbacks "
                + "where provider_payment_id = ?",
            Integer.class, payment.providerPaymentId().value()
        );
    }
}
