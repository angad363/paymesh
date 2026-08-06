package com.paymesh.payment;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.CancelAbandonedPaymentIntentsService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AN ABANDONED CHECKOUT MUST NOT KILL THE ORDER IT IS HOLDING.
 * <p>
 * {@code uq_payment_intents_live_per_order} (ADR-011) frees an order's only slot on FAILED or
 * CANCELLED and nothing else, so an intent left in REQUIRES_PAYMENT_METHOD by a customer who closed
 * the tab holds that order for good: no second intent, no way to mark it paid, and no way to
 * recreate it under the same merchant reference. ADR-015's timeout covers PROCESSING, which is one
 * of the five live states and not the one customers actually strand things in.
 * <p>
 * It is also a cross-tenant availability problem, which is why it is worth a sweep rather than a
 * note. The expiry sweep orders candidates by deadline and skips held orders (ADR-014); a
 * permanently held order has the oldest deadline, sorts first, and is in every batch. Enough of them
 * and no order on the platform is examined again.
 * <p>
 * Not {@code @Transactional}: each cancellation commits in its own transaction and the point is what
 * survives.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AbandonedIntentSweepIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final long ORDER_AMOUNT_MINOR = 2500;

    @Autowired
    private CancelAbandonedPaymentIntentsService sweeper;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Sabotage that must turn this red: drop the {@code abandoned-intents} sweep entirely, or narrow
     * its status list so REQUIRES_PAYMENT_METHOD is not swept.
     */
    @Test
    void cancelsACheckoutNobodyCameBackToAndReleasesTheOrdersSlot() {
        PaymentIntent abandoned = abandonedIntent();

        // The slot is held while the intent is live -- this is the state that used to be permanent.
        assertThat(liveIntentCount(abandoned)).isEqualTo(1);

        sweeper.sweep();

        // Asserted against THIS intent's rows, never the sweep's counters. The sweep is
        // deliberately platform-wide (abandonment is not a tenant-specific event), so examined and
        // cancelled include whatever else the suite left behind -- this test passed in isolation and
        // failed in the full run on exactly that.
        assertThat(statusOf(abandoned)).isEqualTo(PaymentIntentStatus.CANCELLED.name());
        assertThat(liveIntentCount(abandoned))
            .as("cancelling is what releases the order's only slot")
            .isZero();
    }

    /** The timeline must say SYSTEM. A sweeper is not a merchant and must not claim to be. */
    @Test
    void attributesTheCancellationToTheSystemRatherThanTheMerchant() {
        PaymentIntent abandoned = abandonedIntent();

        sweeper.sweep();

        Map<String, Object> transition = jdbc.queryForMap("""
            select from_status, to_status, actor_type, reason
              from payment_state_history
             where payment_intent_id = ? and to_status = 'CANCELLED'
            """, abandoned.paymentIntentId().value());

        assertThat(transition)
            .containsEntry("from_status", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD.name())
            .containsEntry("actor_type", "SYSTEM")
            .containsEntry("reason", "Abandoned before confirmation");
    }

    /**
     * A checkout still inside its window is a customer fetching their card, not an abandonment.
     * <p>
     * Sabotage that must turn this red: sweep with no age check, or with a cutoff of {@code now}.
     */
    @Test
    void leavesACheckoutThatIsStillWithinItsWindowAlone() {
        PaymentIntent fresh = intent(merchantWithOrder());

        sweeper.sweep();

        assertThat(statusOf(fresh))
            .as("a checkout opened moments ago is not abandoned")
            .isEqualTo(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD.name());
    }

    /** Running twice must not write a second transition or a second event. */
    @Test
    void writesNothingASecondTimeWhenTheSweepRunsTwice() {
        PaymentIntent abandoned = abandonedIntent();

        sweeper.sweep();
        sweeper.sweep();

        assertThat(jdbc.queryForObject("""
            select count(*) from payment_state_history
             where payment_intent_id = ? and to_status = 'CANCELLED'
            """, Long.class, abandoned.paymentIntentId().value()))
            .as("one cancellation happened, so one row records it")
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            select count(*) from outbox_events
             where aggregate_id = ? and event_type = 'payment.cancelled'
            """, Long.class, abandoned.paymentIntentId().value()))
            .isEqualTo(1L);
    }

    // --- one bad row ----------------------------------------------------------------------------

    /**
     * ONE UNMAPPABLE ROW COSTS ONE CHECKOUT, NOT THE SWEEP. Open item 2, fourth sweep.
     *
     * <p>The same defect as order expiry, in the sweep that was missed when the first three were
     * fixed: {@code findAbandonedBeforeConfirmation} built a {@code PaymentIntent} per candidate
     * row <b>outside</b> this sweep's per-item try/catch. This row has the oldest
     * {@code updated_at}, so it led every batch, threw out of {@code sweep()} before a single
     * checkout was examined, and the scheduler re-ran it forever.
     *
     * <p>Worse here than in order expiry, because this sweep is what frees
     * {@code uq_payment_intents_live_per_order}. Dead, every abandoned checkout keeps its order's
     * only live slot -- and a permanently held order then also blocks the expiry sweep behind it.
     *
     * <p>It is reachable because {@code merchants.merchant_id} has no format CHECK while
     * {@code MerchantId.from} refuses anything that is not {@code prefix_uuid}.
     *
     * <p><b>Sabotage that must turn this red:</b> make
     * {@code findAbandonedBeforeConfirmation} return mapped aggregates again, or move the
     * {@code MerchantId.from} / {@code PaymentIntentId.from} calls out of the service's try.
     */
    @Test
    void cancelsTheGoodCheckoutsEvenWhenACandidateRowCannotBeMapped() {
        PaymentIntent healthy = abandonedIntent();

        unmappableAbandonedIntent();

        CancelAbandonedPaymentIntentsService.SweepResult result = sweeper.sweep();

        // AT LEAST, never exactly: the sweep is platform-wide and other tests in this class leave
        // abandoned intents behind, so an exact count would assert on test order.
        assertThat(result.errored())
            .as("the bad row is counted as a failure, not thrown out of the sweep")
            .isGreaterThanOrEqualTo(1);

        assertThat(statusOf(healthy))
            .as("THE POINT: the checkout behind the bad row was still cancelled")
            .isEqualTo("CANCELLED");
    }

    /**
     * A row no mapper can rehydrate, inserted the only way one can exist: straight through JDBC,
     * with a merchant id the application would never mint. Backdated furthest, so it sorts to the
     * head of the candidate batch -- which is what made the original bug total rather than partial.
     */
    private void unmappableAbandonedIntent() {
        // Unique, because merchant_id is the primary key and another test in another class plants
        // one of these too. Malformed either way: MerchantId.from wants "mrc_" + a bare UUID.
        // "bad-" + UUID is exactly 40 characters, which is the column width.
        String malformedMerchantId = "bad-" + UUID.randomUUID();
        String orderId = OrderId.generate().value();
        OffsetDateTime longAgo =
            OffsetDateTime.ofInstant(Instant.now().minusSeconds(172_800), ZoneOffset.UTC);

        jdbc.update("""
            insert into merchants
                (merchant_id, business_name, email, country, default_currency, status,
                 created_at, updated_at)
            values (?, 'Corrupt Co', ?, 'IN', 'INR', 'ACTIVE', ?, ?)
            """, malformedMerchantId, UUID.randomUUID() + "@paymesh.test", longAgo, longAgo);

        jdbc.update("""
            insert into orders
                (order_id, merchant_id, amount_minor, amount_paid_minor, currency, status,
                 metadata, version, created_at, updated_at)
            values (?, ?, ?, 0, 'INR', 'PENDING', '{}'::jsonb, 0, ?, ?)
            """, orderId, malformedMerchantId, ORDER_AMOUNT_MINOR, longAgo, longAgo);

        jdbc.update("""
            insert into payment_intents
                (payment_intent_id, merchant_id, order_id, amount_minor, currency, capture_method,
                 status, captured_amount_minor, refunded_amount_minor, metadata, version,
                 created_at, updated_at)
            values (?, ?, ?, ?, 'INR', 'AUTOMATIC', 'REQUIRES_PAYMENT_METHOD', 0, 0, '{}'::jsonb, 0,
                    ?, ?)
            """, "pi_" + UUID.randomUUID(), malformedMerchantId, orderId, ORDER_AMOUNT_MINOR,
            longAgo, longAgo);
    }

    /** Backdated past the configured age, which is the only way to reach the cutoff in a test. */
    private PaymentIntent abandonedIntent() {
        PaymentIntent intent = intent(merchantWithOrder());

        // OffsetDateTime rather than Instant: the driver has no default mapping from Instant to
        // timestamptz, so binding one is a BadSqlGrammar rather than a wrong value.
        jdbc.update(
            "update payment_intents set updated_at = ? where payment_intent_id = ?",
            OffsetDateTime.ofInstant(Instant.now().minusSeconds(86_400), ZoneOffset.UTC),
            intent.paymentIntentId().value()
        );

        return intent;
    }

    private PaymentIntent intent(MerchantAndOrder fixture) {
        return createPaymentIntentService.create(new CreatePaymentIntentCommand(
            fixture.merchantId(), fixture.orderId(), null, ORDER_AMOUNT_MINOR, "INR",
            null, null, Map.of()
        ));
    }

    private MerchantAndOrder merchantWithOrder() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();

        String orderId = orders.save(Order.create(
            OrderId.generate(), merchantId, null, null, ORDER_AMOUNT_MINOR, "INR", null,
            Map.of(), null, CREATED_AT
        )).orderId().value();

        return new MerchantAndOrder(merchantId, orderId);
    }

    private String statusOf(PaymentIntent intent) {
        return jdbc.queryForObject(
            "select status from payment_intents where payment_intent_id = ?",
            String.class,
            intent.paymentIntentId().value()
        );
    }

    /** What the partial unique index actually counts: intents still occupying the order's slot. */
    private Long liveIntentCount(PaymentIntent intent) {
        return jdbc.queryForObject("""
            select count(*) from payment_intents
             where merchant_id = ? and order_id = ?
               and status not in ('FAILED', 'CANCELLED')
            """, Long.class, intent.merchantId().value(), intent.orderId());
    }

    private record MerchantAndOrder(MerchantId merchantId, String orderId) {
    }
}
