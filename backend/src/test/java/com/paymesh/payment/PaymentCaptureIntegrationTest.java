package com.paymesh.payment;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.CancelPaymentIntentService;
import com.paymesh.payment.application.CapturePaymentIntentService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.CaptureAmountExceedsAuthorizedException;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentNotCapturableException;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderCallbackOutcome;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manual capture against a real PostgreSQL (design spec section 5).
 * <p>
 * The reason this class exists rather than only the plain-JUnit one:
 * {@code ck_payment_intents_captured} and {@code ck_payment_intents_succeeded_captured} are the
 * actual guarantees against overcapture and against a succeeded-but-empty payment, and neither can
 * be exercised by a list of objects. Two tests here go round the application entirely so that what
 * answers is PostgreSQL and nothing else.
 * <p>
 * Deliberately NOT {@code @Transactional}: an outer test transaction would defer the constraint
 * failures these tests are about. Each test registers its own merchant and scopes its queries to it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class PaymentCaptureIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final long ORDER_AMOUNT_MINOR = 1999;
    private static final String PROVIDER = "SIMULATOR";

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private CapturePaymentIntentService capturePaymentIntentService;

    @Autowired
    private CancelPaymentIntentService cancelPaymentIntentService;

    @Autowired
    private GetPaymentIntentService getPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService callbacks;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    // --- full capture --------------------------------------------------------------------

    /**
     * THE WHOLE FOOTPRINT OF A CAPTURE: the intent moves, ONE timeline row, ONE event. The counts are
     * as load-bearing as the values -- two {@code payment.succeeded} events for one capture is a
     * collection announced twice.
     * <p>
     * SUCCEEDED here is operational state and nothing else (design spec 0.5): no balance moved, no
     * ledger entry exists, and {@code orders.status} is deliberately still PENDING.
     */
    @Test
    void capturesTheFullAmountAndWritesOneOfEachRow() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);

        PaymentIntent captured = capturePaymentIntentService.capture(
            fixture.merchantId(), fixture.intentId(), null
        );

        assertThat(captured.status()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(captured.capturedAmountMinor()).isEqualTo(ORDER_AMOUNT_MINOR);

        assertThat(row(fixture))
            .containsEntry("status", "SUCCEEDED")
            .containsEntry("captured_amount_minor", ORDER_AMOUNT_MINOR);

        assertThat(transitions(fixture)).endsWith("AUTHORIZED->SUCCEEDED");
        assertThat(actorOfTheLastTransition(fixture))
            .as("the provider authorized; the MERCHANT decided to collect")
            .isEqualTo("MERCHANT");
        assertThat(eventTypes(fixture)).containsExactly(
            "payment.created", "payment.method_attached", "payment.processing",
            "payment.authorized", "payment.succeeded"
        );
        assertThat(orderStatusOf(fixture))
            .as("Payment does not write the orders table -- design spec 0.5")
            .isEqualTo("PENDING");
    }

    // --- partial capture -----------------------------------------------------------------

    /**
     * PARTIAL CAPTURE, AND THE ROW SATISFIES BOTH CHECKS AT ONCE.
     * {@code ck_payment_intents_captured} wants captured <= amount and
     * {@code ck_payment_intents_succeeded_captured} wants captured > 0 on a SUCCEEDED row -- a
     * partial capture is the only state where both are non-trivially true, so this is the row that
     * proves they can coexist.
     * <p>
     * The gap between the two columns is what will make {@code orders.PARTIALLY_PAID} reachable, via
     * the {@code payment.succeeded} consumer that does not exist yet.
     */
    @Test
    void capturesLessThanAuthorizedAndStillReachesSucceeded() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);

        capturePaymentIntentService.capture(fixture.merchantId(), fixture.intentId(), 500L);

        assertThat(row(fixture))
            .containsEntry("status", "SUCCEEDED")
            .containsEntry("captured_amount_minor", 500L)
            .containsEntry("amount_minor", ORDER_AMOUNT_MINOR);

        assertThat(capturedAmountOfTheLastEvent(fixture))
            .as("the consumer must be told the real figure, not the authorized one")
            .isEqualTo("500");
    }

    // --- what PostgreSQL refuses, with no Java in the path ----------------------------------

    /**
     * THE CHECK ITSELF. {@code ck_payment_intents_captured} is the guarantee against overcapture; the
     * aggregate's check exists only to turn the refusal into a readable 422.
     * <p>
     * This UPDATE goes round the application entirely -- no service, no aggregate, no Hibernate
     * dirty-checking -- so what answers is the constraint and nothing else. A test that went through
     * the service would pass with the CHECK dropped, which is exactly the trap
     * {@code refusesTwoRawCallbackRowsSharingAProviderAndEventId} was written to avoid on the
     * callback side.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop {@code ck_payment_intents_captured} from V8.
     */
    @Test
    void refusesARawUpdateCapturingMoreThanTheIntentAuthorized() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);

        assertThatThrownBy(() -> jdbc.update(
            "update payment_intents set captured_amount_minor = ? where payment_intent_id = ?",
            ORDER_AMOUNT_MINOR + 1,
            fixture.intentId().value()
        ))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_payment_intents_captured");

        assertThat(row(fixture)).containsEntry("captured_amount_minor", 0L);
    }

    /**
     * THE OTHER CHECK. A SUCCEEDED intent that captured nothing is a contradiction the Ledger would
     * later have to reconcile, and {@code ck_payment_intents_succeeded_captured} refuses to store it.
     * The aggregate refuses a zero capture too; this proves the row could not exist even if it did
     * not.
     */
    @Test
    void refusesARawUpdateMarkingAnIntentSucceededWithNothingCaptured() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);

        assertThatThrownBy(() -> jdbc.update(
            "update payment_intents set status = 'SUCCEEDED' where payment_intent_id = ?",
            fixture.intentId().value()
        ))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_payment_intents_succeeded_captured");
    }

    /**
     * THE SAME OVERCAPTURE, THROUGH THE SERVICE, ANSWERED BY THE AGGREGATE. Nothing is written and
     * the caller gets an exception the API maps to 422 rather than a constraint violation it would
     * have to map to 500.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the {@code requestedAmountMinor > amountMinor}
     * branch from {@code PaymentIntent.capture}. The exception type changes to
     * {@code DataIntegrityViolationException} as {@code ck_payment_intents_captured} takes the
     * refusal instead -- which is the CHECK working and the API answer being wrong.
     */
    @Test
    void refusesAnOvercaptureThroughTheServiceBeforeTheDatabaseSeesIt() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);

        assertThatThrownBy(() -> capturePaymentIntentService.capture(
            fixture.merchantId(), fixture.intentId(), ORDER_AMOUNT_MINOR + 1
        )).isInstanceOf(CaptureAmountExceedsAuthorizedException.class);

        assertThat(row(fixture))
            .containsEntry("status", "AUTHORIZED")
            .containsEntry("captured_amount_minor", 0L);
        assertThat(eventTypes(fixture)).doesNotContain("payment.succeeded");
    }

    // --- what is refused ---------------------------------------------------------------------

    /** A second capture finds SUCCEEDED. The state machine, not luck, is what stops a double collect. */
    @Test
    void refusesASecondCapture() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);
        capturePaymentIntentService.capture(fixture.merchantId(), fixture.intentId(), null);

        assertThatThrownBy(() -> capturePaymentIntentService.capture(
            fixture.merchantId(), fixture.intentId(), null
        )).isInstanceOf(PaymentIntentNotCapturableException.class);

        assertThat(eventTypes(fixture)).containsOnlyOnce("payment.succeeded");
    }

    /** Capturing a PROCESSING intent is a 409's worth of wrong: nothing is being held yet. */
    @Test
    void refusesToCaptureAnIntentThatIsStillProcessing() {
        Fixture fixture = processing(CaptureMethod.MANUAL);

        assertThatThrownBy(() -> capturePaymentIntentService.capture(
            fixture.merchantId(), fixture.intentId(), null
        )).isInstanceOf(PaymentIntentNotCapturableException.class);

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.PROCESSING);
    }

    /** An AUTOMATIC intent is the provider's to capture; two collectors on one authorization is the bug. */
    @Test
    void refusesToCaptureAnAutomaticIntent() {
        Fixture fixture = authorized(CaptureMethod.AUTOMATIC);

        assertThatThrownBy(() -> capturePaymentIntentService.capture(
            fixture.merchantId(), fixture.intentId(), null
        )).isInstanceOf(PaymentIntentNotCapturableException.class);

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.AUTHORIZED);
    }

    // --- AUTHORIZED to CANCELLED (ADR-011's slot table) -----------------------------------------

    /**
     * RELEASING AN AUTHORIZATION INSTEAD OF COLLECTING IT, which ADR-011's slot table requires: a
     * MANUAL intent parked at AUTHORIZED is a state a merchant can sit in indefinitely, and without
     * this route the order's only slot is held forever by funds nobody intends to take.
     * <p>
     * The slot really is released here -- proved by creating a second intent for the same order,
     * which {@code uq_payment_intents_live_per_order} would otherwise refuse.
     */
    @Test
    void cancelsAnAuthorizedIntentAndReleasesTheOrdersSlot() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);

        cancelPaymentIntentService.cancel(
            fixture.merchantId(), fixture.intentId(), "decided against it"
        );

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.CANCELLED);
        assertThat(transitions(fixture)).endsWith("AUTHORIZED->CANCELLED");
        assertThat(eventTypes(fixture)).endsWith("payment.cancelled");

        PaymentIntent second = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            fixture.merchantId(), fixture.orderId(), null, ORDER_AMOUNT_MINOR, "INR",
            CaptureMethod.MANUAL, null, Map.of()
        ));

        assertThat(second.status())
            .as("the slot is free, so a fresh collection may be started")
            .isEqualTo(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
    }

    /** Captured funds cannot be un-captured by cancelling. Reversal belongs to the Refund capability. */
    @Test
    void refusesToCancelACapturedIntent() {
        Fixture fixture = authorized(CaptureMethod.MANUAL);
        capturePaymentIntentService.capture(fixture.merchantId(), fixture.intentId(), null);

        assertThatThrownBy(() -> cancelPaymentIntentService.cancel(
            fixture.merchantId(), fixture.intentId(), "second thoughts"
        )).isInstanceOf(com.paymesh.payment.domain.PaymentIntentNotCancellableException.class);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** A merchant, an order, an intent confirmed and then AUTHORIZED by a provider callback. */
    private Fixture authorized(CaptureMethod captureMethod) {
        Fixture fixture = processing(captureMethod);

        ProviderCallbackOutcome outcome = callbacks.record(new RecordProviderCallbackCommand(
            PROVIDER,
            new ProviderEvent(
                "sim_evt_" + UUID.randomUUID(), PROVIDER_EVENT, fixture.intentId().value(),
                "sim_pay_" + UUID.randomUUID(), ProviderOutcome.AUTHORIZED,
                ORDER_AMOUNT_MINOR, null, null, null, null
            ),
            hashOf(UUID.randomUUID().toString())
        ));

        assertThat(outcome).isEqualTo(ProviderCallbackOutcome.APPLIED);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.AUTHORIZED);

        return fixture;
    }

    /** The same, stopped one step earlier: PROCESSING with one attempt open. */
    private Fixture processing(CaptureMethod captureMethod) {
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
            merchantId, orderId, null, ORDER_AMOUNT_MINOR, "INR", captureMethod, null, Map.of()
        ));

        attachPaymentMethodService.attach(merchantId, intent.paymentIntentId(), PaymentMethodType.CARD);
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        return new Fixture(merchantId, intent.paymentIntentId(), orderId);
    }

    private PaymentIntentStatus statusOf(Fixture fixture) {
        return getPaymentIntentService.getById(fixture.merchantId(), fixture.intentId()).status();
    }

    private Map<String, Object> row(Fixture fixture) {
        return jdbc.queryForList("""
            select status, amount_minor, captured_amount_minor, refunded_amount_minor
              from payment_intents
             where payment_intent_id = ?
            """, fixture.intentId().value()).get(0);
    }

    private String orderStatusOf(Fixture fixture) {
        return jdbc.queryForObject(
            "select status from orders where order_id = ?", String.class, fixture.orderId()
        );
    }

    private List<String> transitions(Fixture fixture) {
        return jdbc.queryForList("""
            select coalesce(from_status, 'null') || '->' || to_status as move
              from payment_state_history
             where merchant_id = ?
             order by occurred_at, payment_state_history_id
            """, String.class, fixture.merchantId().value());
    }

    private String actorOfTheLastTransition(Fixture fixture) {
        return jdbc.queryForList("""
            select actor_type from payment_state_history
             where merchant_id = ?
             order by occurred_at desc, payment_state_history_id desc
             limit 1
            """, String.class, fixture.merchantId().value()).get(0);
    }

    private List<String> eventTypes(Fixture fixture) {
        return jdbc.queryForList(
            "select event_type from outbox_events where merchant_id = ? order by occurred_at, event_id",
            String.class,
            fixture.merchantId().value()
        );
    }

    private String capturedAmountOfTheLastEvent(Fixture fixture) {
        return jdbc.queryForList("""
            select payload ->> 'capturedAmountMinor'
              from outbox_events
             where merchant_id = ? and event_type = 'payment.succeeded'
             order by occurred_at desc, event_id desc
             limit 1
            """, String.class, fixture.merchantId().value()).get(0);
    }

    /** Stands in for the signature filter's hash of the raw body: 64 hex characters, distinct. */
    private static String hashOf(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fixture(MerchantId merchantId, PaymentIntentId intentId, String orderId) {
    }
}
