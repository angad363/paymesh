package com.paymesh.refund;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.MerchantBalance;
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
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.refund.application.CreateRefundCommand;
import com.paymesh.refund.application.CreateRefundService;
import com.paymesh.refund.application.RecordRefundCallbackCommand;
import com.paymesh.refund.application.RecordRefundCallbackService;
import com.paymesh.refund.application.RefundAlreadyRequestedException;
import com.paymesh.refund.application.RefundExceedsCapturedAmountException;
import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundCallbackOutcome;
import com.paymesh.refund.domain.RefundEvent;
import com.paymesh.refund.domain.RefundOutcome;
import com.paymesh.refund.domain.RefundStatus;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE REFUND LOOP AND THE GUARDS UNDER IT, against a real PostgreSQL.
 * <p>
 * Two halves. The first drives the whole chain -- collect, refund, provider callback, relay -- and
 * asserts that the money came back in the ledger and that the payment says so. The second goes
 * round the application entirely, with raw JDBC, and asserts the database refuses what the
 * application would also have refused.
 * <p>
 * Deliberately NOT {@code @Transactional}: {@code tr_refunds_within_captured} is DEFERRED and fires
 * only at COMMIT, so a rolled-back test would never reach the most important constraint in V16.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class RefundIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final Instant REFUND_EVENT = Instant.parse("2026-08-02T12:00:00Z");
    private static final long CAPTURED = 99900;
    private static final String PROVIDER = "SIMULATOR";

    @Autowired
    private CreateRefundService createRefundService;

    @Autowired
    private RecordRefundCallbackService refundCallbacks;

    @Autowired
    private GetBalancesService balances;

    @Autowired
    private GetPaymentIntentService paymentIntents;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService paymentCallbacks;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcClient jdbc;

    // --- THE LOOP -------------------------------------------------------------------------------

    /**
     * THE HEADLINE, AND THE LAST CAPABILITY IN PHASE 1: money goes back out, the ledger records the
     * reversal, and the payment says REFUNDED.
     * <p>
     * Three modules move and none of them calls another. Refund announces {@code refund.succeeded};
     * the Ledger posts a reversal because it subscribed; Payment moves its own status because it
     * subscribed. {@code ModuleBoundaryTest} keeps every allowlist involved empty.
     */
    @Test
    void putsTheMoneyBackAndRecordsItEverywhere() {
        Fixture fixture = collected();

        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", CAPTURED));

        Refund refund = refund(fixture, CAPTURED);
        settle(refund, RefundOutcome.SUCCEEDED);
        drain();

        assertThat(pendingBalance(fixture.merchantId()))
            .as("the reversal took the balance back to nothing")
            .containsExactly(new MerchantBalance("INR", 0L));

        PaymentIntent intent = paymentIntents.getById(fixture.merchantId(), fixture.intentId());

        assertThat(intent.status()).isEqualTo(PaymentIntentStatus.REFUNDED);
        assertThat(intent.refundedAmountMinor()).isEqualTo(CAPTURED);
    }

    /**
     * THE REVERSAL IS A NEW JOURNAL, NOT AN EDIT. Both remain in the history -- which is the entire
     * point of an append-only ledger, and the reason V15's immutability triggers exist.
     */
    @Test
    void leavesTheCaptureJournalUntouchedAndWritesASecondOne() {
        Fixture fixture = collected();
        settle(refund(fixture, CAPTURED), RefundOutcome.SUCCEEDED);
        drain();

        assertThat(journalTypes(fixture.merchantId()))
            .containsExactly("PAYMENT_CAPTURED", "REFUND_REVERSAL");

        assertThat(entriesOf(fixture.merchantId(), "REFUND_REVERSAL"))
            .as("the capture debited clearing and credited the merchant; this does the opposite")
            .containsExactlyInAnyOrder("DEBIT:MERCHANT_PENDING", "CREDIT:PROVIDER_CLEARING");
    }

    /** A partial refund leaves the payment PARTIALLY_REFUNDED, a status unreachable until now. */
    @Test
    void reachesPartiallyRefundedOnAPartialRefund() {
        Fixture fixture = collected();
        settle(refund(fixture, 30000), RefundOutcome.SUCCEEDED);
        drain();

        PaymentIntent intent = paymentIntents.getById(fixture.merchantId(), fixture.intentId());

        assertThat(intent.status()).isEqualTo(PaymentIntentStatus.PARTIALLY_REFUNDED);
        assertThat(intent.refundedAmountMinor()).isEqualTo(30000);
        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", CAPTURED - 30000));
    }

    /** Two partial refunds add up to the whole, and the payment lands on REFUNDED. */
    @Test
    void accumulatesPartialRefundsToRefunded() {
        Fixture fixture = collected();

        settle(refund(fixture, 40000), RefundOutcome.SUCCEEDED);
        drain();
        settle(refund(fixture, CAPTURED - 40000), RefundOutcome.SUCCEEDED);
        drain();

        assertThat(paymentIntents.getById(fixture.merchantId(), fixture.intentId()).status())
            .isEqualTo(PaymentIntentStatus.REFUNDED);
        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", 0L));
    }

    /** A failed refund moves no money: no reversal, no change to the payment. */
    @Test
    void movesNothingWhenTheProviderRefuses() {
        Fixture fixture = collected();

        settle(refund(fixture, CAPTURED), RefundOutcome.FAILED);
        drain();

        assertThat(journalTypes(fixture.merchantId())).containsExactly("PAYMENT_CAPTURED");
        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", CAPTURED));
        assertThat(paymentIntents.getById(fixture.merchantId(), fixture.intentId()).status())
            .isEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    /** And its amount is released, so the full amount can be refunded on a second attempt. */
    @Test
    void releasesTheAmountAFailedRefundHeld() {
        Fixture fixture = collected();
        settle(refund(fixture, CAPTURED), RefundOutcome.FAILED);

        assertThat(refund(fixture, CAPTURED).amountMinor()).isEqualTo(CAPTURED);
    }

    // --- callbacks --------------------------------------------------------------------------------

    @Test
    void appliesARefundCallbackExactlyOnceHoweverOftenItArrives() {
        Fixture fixture = collected();
        Refund refund = refund(fixture, CAPTURED);
        RefundEvent event = succeeded(refund);

        assertThat(record(event)).isEqualTo(RefundCallbackOutcome.APPLIED);
        assertThat(record(event)).isEqualTo(RefundCallbackOutcome.DUPLICATE);
        assertThat(record(event)).isEqualTo(RefundCallbackOutcome.DUPLICATE);

        drain();

        assertThat(journalTypes(fixture.merchantId()))
            .containsExactly("PAYMENT_CAPTURED", "REFUND_REVERSAL");
    }

    /**
     * A LATE FAILURE MUST NOT OVERWRITE AN EARLIER SUCCESS. ADR-012's ordering rule, applied to
     * refunds: the provider's clock decides, not the arrival order.
     */
    @Test
    void refusesACallbackOlderThanOneAlreadyApplied() {
        Fixture fixture = collected();
        Refund refund = refund(fixture, CAPTURED);

        record(new RefundEvent(
            "evt-" + UUID.randomUUID(), REFUND_EVENT, refund.refundId().value(),
            "prov_re_1", RefundOutcome.SUCCEEDED, null, null
        ));

        RefundCallbackOutcome stale = record(new RefundEvent(
            "evt-" + UUID.randomUUID(), REFUND_EVENT.minusSeconds(60), refund.refundId().value(),
            null, RefundOutcome.FAILED, "declined", "too late"
        ));

        assertThat(stale).isEqualTo(RefundCallbackOutcome.STALE);
        assertThat(statusOf(refund)).isEqualTo(RefundStatus.SUCCEEDED.name());
    }

    /** A second, newer callback for a refund already settled is recorded and not applied. */
    @Test
    void doesNotReopenASettledRefund() {
        Fixture fixture = collected();
        Refund refund = refund(fixture, CAPTURED);

        record(succeeded(refund));

        RefundCallbackOutcome second = record(new RefundEvent(
            "evt-" + UUID.randomUUID(), REFUND_EVENT.plusSeconds(60), refund.refundId().value(),
            null, RefundOutcome.FAILED, "declined", "changed our mind"
        ));

        assertThat(second).isEqualTo(RefundCallbackOutcome.NOT_APPLICABLE);
        assertThat(statusOf(refund)).isEqualTo(RefundStatus.SUCCEEDED.name());
    }

    /** A callback naming a refund that does not exist tells the caller nothing about what does. */
    @Test
    void answersNotApplicableForAnUnknownRefund() {
        assertThat(record(new RefundEvent(
            "evt-" + UUID.randomUUID(), REFUND_EVENT, "ref_" + UUID.randomUUID(),
            null, RefundOutcome.SUCCEEDED, null, null
        )))
            .isEqualTo(RefundCallbackOutcome.NOT_APPLICABLE);
    }

    // --- WHAT THE DATABASE REFUSES ----------------------------------------------------------------

    /**
     * THE INVARIANT V16 EXISTS FOR, WITH THE APPLICATION ENTIRELY OUT OF THE PATH.
     * <p>
     * Two refunds are inserted by hand, each individually smaller than the capture and jointly
     * larger. {@code CreateRefundService} would have refused the second; nothing here consults it.
     * The trigger is deferred, so both INSERTs succeed and the failure arrives at COMMIT -- which is
     * the only moment the set is final.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop {@code tr_refunds_within_captured} from V16.
     * Every Java-level test stays green.
     */
    @Test
    void refusesAnOverRefundAtCommitEvenFromRawSql() {
        Fixture fixture = collected();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            insertRefund(fixture, 60000, "SUCCEEDED");
            insertRefund(fixture, 60000, "SUCCEEDED");
            return null;
        }))
            .hasStackTraceContaining("exceeds the");
    }

    /** One refund larger than the capture, same guard. */
    @Test
    void refusesASingleRefundLargerThanTheCapture() {
        Fixture fixture = collected();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            insertRefund(fixture, CAPTURED + 1, "PENDING");
            return null;
        }))
            .hasStackTraceContaining("exceeds the");
    }

    /** FAILED does not count, so a failed refund larger than the capture is legal at the schema. */
    @Test
    void allowsAFailedRefundThatWouldOtherwiseOvershoot() {
        Fixture fixture = collected();

        transactionTemplate.execute(status -> {
            insertRefund(fixture, CAPTURED, "SUCCEEDED");
            insertRefund(fixture, CAPTURED, "FAILED");
            return null;
        });

        assertThat(refundRowCount(fixture)).isEqualTo(2);
    }

    /**
     * A STATUS MOVING BACK OUT OF FAILED RE-ARMS AN AMOUNT THE CHECK HAD DISCOUNTED, which is why
     * the trigger fires on UPDATE and not only on INSERT. Nothing does this today; the trigger does
     * not depend on nothing doing it.
     */
    @Test
    void refusesAnUpdateThatReArmsADiscountedAmount() {
        Fixture fixture = collected();

        String failedId = transactionTemplate.execute(status -> {
            insertRefund(fixture, CAPTURED, "SUCCEEDED");
            return insertRefund(fixture, CAPTURED, "FAILED");
        });

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
            jdbc.sql("update refunds set status = 'PROCESSING' where refund_id = ?")
                .param(failedId)
                .update()
        ))
            .hasStackTraceContaining("exceeds the");
    }

    /**
     * 5000 JPY AGAINST A 5000 INR CAPTURE PASSES THE AMOUNT CHECK EXACTLY, because the trigger
     * compares integers and integers carry no currency. The money going out would be roughly sixty
     * times the money that came in, with every other constraint satisfied.
     * <p>
     * Unreachable through the API -- the request record has no currency field at all -- which is
     * exactly why the schema has to say it too.
     */
    @Test
    void refusesARefundInADifferentCurrencyFromThePayment() {
        Fixture fixture = collected();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            jdbc.sql("""
                    insert into refunds (refund_id, merchant_id, payment_intent_id, amount_minor,
                                         currency, status, created_at, updated_at)
                    values (?, ?, ?, 5000, 'JPY', 'PENDING', ?, ?)
                    """)
                .params(
                    "ref_" + UUID.randomUUID(), fixture.merchantId().value(),
                    fixture.intentId().value(),
                    CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC)
                )
                .update();

            return null;
        }))
            .hasStackTraceContaining("but payment intent");
    }

    /** A refund naming a payment that does not exist cannot be checked, so it is refused. */
    @Test
    void refusesARefundAgainstAPaymentThatDoesNotExist() {
        MerchantId merchantId = existingMerchant();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            jdbc.sql("""
                    insert into refunds (refund_id, merchant_id, payment_intent_id, amount_minor,
                                         currency, status, created_at, updated_at)
                    values (?, ?, ?, 100, 'INR', 'PENDING', ?, ?)
                    """)
                .params(
                    "ref_" + UUID.randomUUID(), merchantId.value(), "pi_" + UUID.randomUUID(),
                    CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC)
                )
                .update();

            return null;
        }))
            .hasStackTraceContaining("does not exist");
    }

    /** Two merchants may both use "REFUND-1"; one merchant may not use it twice. */
    @Test
    void refusesADuplicateMerchantReferenceWithinOneMerchant() {
        Fixture fixture = collected();
        String reference = "REFUND-" + UUID.randomUUID();

        createRefundService.create(new CreateRefundCommand(
            fixture.merchantId(), fixture.intentId().value(), 100L, reference, null, "usr_1"
        ));

        assertThatThrownBy(() -> createRefundService.create(new CreateRefundCommand(
            fixture.merchantId(), fixture.intentId().value(), 100L, reference, null, "usr_1"
        )))
            // The NAMED exception, not the raw constraint: the adapter's job is to translate
            // uq_refunds_merchant_reference into something the API layer can map to a 409, and a
            // test asserting on the constraint name would pass even if that translation were gone.
            .isInstanceOf(RefundAlreadyRequestedException.class)
            .hasMessageContaining(reference);
    }

    /** The same reference under a different merchant is fine -- the uniqueness is per tenant. */
    @Test
    void allowsTwoMerchantsToUseTheSameReference() {
        Fixture first = collected();
        Fixture second = collected();
        String reference = "REFUND-" + UUID.randomUUID();

        createRefundService.create(new CreateRefundCommand(
            first.merchantId(), first.intentId().value(), 100L, reference, null, "usr_1"
        ));

        assertThat(createRefundService.create(new CreateRefundCommand(
            second.merchantId(), second.intentId().value(), 100L, reference, null, "usr_1"
        )).merchantReference())
            .isEqualTo(reference);
    }

    /** The application refuses the ordinary over-refund with a sentence, before the trigger fires. */
    @Test
    void answersAnOrdinaryOverRefundWithAReadableError() {
        Fixture fixture = collected();

        assertThatThrownBy(() -> refund(fixture, CAPTURED + 1))
            .isInstanceOf(RefundExceedsCapturedAmountException.class)
            .hasMessageContaining(String.valueOf(CAPTURED));
    }

    // --- helpers ----------------------------------------------------------------------------------

    private record Fixture(MerchantId merchantId, OrderId orderId, PaymentIntentId intentId) {
    }

    private void drain() {
        while (relay.publish().published() > 0) {
            // the suite shares one container; a pass can be consumed by another test's backlog
        }
    }

    /** Order to collected payment, with the capture journal already posted. */
    private Fixture collected() {
        MerchantId merchantId = existingMerchant();

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

        paymentCallbacks.record(new RecordProviderCallbackCommand(
            PROVIDER,
            new ProviderEvent(
                "evt-succeed-" + UUID.randomUUID(), PROVIDER_EVENT, intent.paymentIntentId().value(),
                null, ProviderOutcome.SUCCEEDED, null, CAPTURED, null, null, null
            ),
            payloadHash()
        ));

        drain();

        return new Fixture(merchantId, order.orderId(), intent.paymentIntentId());
    }

    private Refund refund(Fixture fixture, long amountMinor) {
        return createRefundService.create(new CreateRefundCommand(
            fixture.merchantId(), fixture.intentId().value(), amountMinor, null,
            "Customer changed their mind", "usr_1"
        ));
    }

    private void settle(Refund refund, RefundOutcome outcome) {
        record(new RefundEvent(
            "evt-" + UUID.randomUUID(), REFUND_EVENT, refund.refundId().value(),
            outcome == RefundOutcome.SUCCEEDED ? "prov_re_" + UUID.randomUUID() : null,
            outcome,
            outcome == RefundOutcome.FAILED ? "declined" : null,
            outcome == RefundOutcome.FAILED ? "The issuer declined the refund." : null
        ));
    }

    private RefundCallbackOutcome record(RefundEvent event) {
        return refundCallbacks.record(
            new RecordRefundCallbackCommand(PROVIDER, event, payloadHash())
        );
    }

    private static RefundEvent succeeded(Refund refund) {
        return new RefundEvent(
            "evt-" + UUID.randomUUID(), REFUND_EVENT, refund.refundId().value(),
            "prov_re_1", RefundOutcome.SUCCEEDED, null, null
        );
    }

    private static String payloadHash() {
        return (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
    }

    private List<MerchantBalance> pendingBalance(MerchantId merchantId) {
        return balances.forMerchant(merchantId);
    }

    private List<String> journalTypes(MerchantId merchantId) {
        return jdbc.sql("""
                select transaction_type from ledger_transactions
                 where merchant_id = ? order by created_at, transaction_type
                """)
            .param(merchantId.value())
            .query(String.class)
            .list();
    }

    private List<String> entriesOf(MerchantId merchantId, String transactionType) {
        return jdbc.sql("""
                select e.direction || ':' || a.account_type
                  from ledger_entries e
                  join ledger_accounts a on a.ledger_account_id = e.ledger_account_id
                  join ledger_transactions t on t.ledger_transaction_id = e.ledger_transaction_id
                 where t.merchant_id = ? and t.transaction_type = ?
                """)
            .params(merchantId.value(), transactionType)
            .query(String.class)
            .list();
    }

    private String statusOf(Refund refund) {
        return jdbc.sql("select status from refunds where refund_id = ?")
            .param(refund.refundId().value())
            .query(String.class)
            .single();
    }

    private long refundRowCount(Fixture fixture) {
        return jdbc.sql("select count(*) from refunds where payment_intent_id = ?")
            .param(fixture.intentId().value())
            .query(Long.class)
            .single();
    }

    /** Raw JDBC, going round CreateRefundService entirely. Returns the id it minted. */
    private String insertRefund(Fixture fixture, long amountMinor, String status) {
        String refundId = "ref_" + UUID.randomUUID();

        jdbc.sql("""
                insert into refunds (refund_id, merchant_id, payment_intent_id, amount_minor,
                                     currency, status, created_at, updated_at)
                values (?, ?, ?, ?, 'INR', ?, ?, ?)
                """)
            .params(
                refundId,
                fixture.merchantId().value(),
                fixture.intentId().value(),
                amountMinor,
                status,
                // OffsetDateTime, not Instant: the JDBC driver cannot infer a SQL type for the
                // latter. Hibernate can, which is why the application's own writes do not need this.
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
            )
            .update();

        return refundId;
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Refund Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();
    }
}
