package com.paymesh.order;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.CancelOrderService;
import com.paymesh.order.application.CreateOrderCommand;
import com.paymesh.order.application.CreateOrderService;
import com.paymesh.order.application.ExpireOrdersService;
import com.paymesh.order.application.ExpireOrdersService.SweepResult;
import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.application.OrderStateHistoryRepository;
import com.paymesh.order.application.PaymentActivityLookup;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.CancelPaymentIntentService;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order expiry sweep and {@code order_state_history}, against a real PostgreSQL (ADR-014, V11).
 * <p>
 * Deliberately NOT {@code @Transactional}. The sweep opens a transaction PER ORDER and its
 * idempotency turns on what the first run actually committed; an outer test transaction would make
 * the double-run test pass whether or not the code was right. Every test registers its own merchant
 * and scopes its queries to it, so rows surviving between tests cannot make one test's assertions
 * depend on another's.
 * <p>
 * <b>The scheduler is not in the picture.</b> {@code paymesh.orders.expiry-sweep.enabled} is false
 * under the dev profile the suite runs with, and these tests call {@code sweep()} directly -- which
 * is the whole reason the logic lives in a plain service rather than in the {@code @Scheduled}
 * class.
 * <p>
 * <b>The assertions are per-merchant, never on {@link SweepResult}'s counts.</b> The sweep is
 * platform-wide by design, so it also picks up expirable orders another test in this class left
 * behind -- "expired exactly 1" would then pass or fail on test ORDER rather than on behaviour.
 * Each test therefore asserts the status, the timeline and the events of ITS OWN merchant, which no
 * other test can touch. The counts are asserted in {@code ExpireOrdersServiceTest}, where the world
 * is one in-memory list.
 * <p>
 * <b>It reaches into the Payment module on purpose.</b> The one thing a plain-JUnit test cannot
 * prove is that {@code PaymentActivityLookup}'s real implementation agrees with
 * {@code uq_payment_intents_live_per_order} about what "live" means. Only a real intent in a real
 * table can show that, and if the two ever disagree the sweeper expires orders that are being paid.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class OrderExpiryIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T09:00:00Z");

    /**
     * RELATIVE TO THE REAL CLOCK, not a literal, and that is not laziness about determinism.
     * <p>
     * Orders here are created through {@code CreateOrderService}, which stamps {@code createdAt} from
     * the application's real {@code Clock} bean -- and {@code Order.create} refuses an expiry that is
     * not after it. A fixed literal would therefore be valid only until the wall clock passed it, and
     * the suite would start failing on a date rather than on a change.
     * <p>
     * Determinism comes from the other end: the SWEEP's clock is fixed per test, so what "now" means
     * to the code under test is exact.
     */
    private static final Instant EXPIRES_AT = Instant.now().plus(Duration.ofHours(1));

    private static final Instant AFTER_EXPIRY = EXPIRES_AT.plus(Duration.ofHours(1));

    private static final long ORDER_AMOUNT_MINOR = 1999;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrderStateHistoryRepository history;

    @Autowired
    private GetOrderService getOrderService;

    @Autowired
    private CreateOrderService createOrderService;

    @Autowired
    private CancelOrderService cancelOrderService;

    @Autowired
    private PaymentActivityLookup payments;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private CancelPaymentIntentService cancelPaymentIntentService;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private JdbcTemplate jdbc;

    // --- the sweep -----------------------------------------------------------------------

    /**
     * AN ELIGIBLE ORDER EXPIRES, and its whole footprint is one status change, one timeline row and
     * one event. The counts matter as much as the values.
     */
    @Test
    void expiresAnEligibleOrderAndWritesOneOfEachRow() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);

        sweeperAt(AFTER_EXPIRY).sweep();

        assertThat(statusOf(merchantId, orderId)).isEqualTo(OrderStatus.EXPIRED);

        assertThat(transitions(merchantId)).containsExactly("null->PENDING", "PENDING->EXPIRED");
        assertThat(actorsOf(merchantId)).containsExactly("MERCHANT", "SYSTEM");
        assertThat(eventTypes(merchantId)).containsExactly("order.created", "order.expired");
    }

    /**
     * SYSTEM WITH NO ACTOR ID, written straight to the column. There is no principal behind a timer,
     * and {@code ck_order_state_history_actor} admits only MERCHANT and SYSTEM -- so an implementation
     * that reached for PROVIDER would be refused by the database rather than by a Java assertion.
     */
    @Test
    void attributesTheExpiryToTheSystemWithNoPrincipal() {
        MerchantId merchantId = existingMerchant();
        expiringOrder(merchantId);

        sweeperAt(AFTER_EXPIRY).sweep();

        Map<String, Object> row = jdbc.queryForList("""
            select actor_type, actor_id, reason, from_status, to_status
              from order_state_history
             where merchant_id = ? and to_status = 'EXPIRED'
            """, merchantId.value()).get(0);

        assertThat(row)
            .containsEntry("actor_type", "SYSTEM")
            .containsEntry("actor_id", null)
            .containsEntry("from_status", "PENDING")
            .containsEntry("to_status", "EXPIRED");
        assertThat(row.get("reason").toString()).contains("expiresAt");
    }

    /** A deadline in the future is not a passed deadline, and no transaction is even opened. */
    @Test
    void leavesAnOrderWhoseDeadlineHasNotArrived() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);

        sweeperAt(EXPIRES_AT.minusSeconds(1)).sweep();

        assertThat(statusOf(merchantId, orderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(transitions(merchantId)).containsExactly("null->PENDING");
    }

    /**
     * NO DEADLINE MEANS NEVER EXPIRES, proved against the real partial index rather than a stream
     * filter. {@code idx_orders_expirable} is {@code WHERE status = 'PENDING' AND expires_at IS NOT
     * NULL}, and the JPQL repeats both predicates -- if either were dropped, every open-ended order
     * on the platform would be swept on the first run.
     */
    @Test
    void neverExpiresAnOrderThatSetNoDeadline() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = orderExpiringAt(merchantId, null);

        sweeperAt(AFTER_EXPIRY.plusSeconds(999_999)).sweep();

        assertThat(statusOf(merchantId, orderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(transitions(merchantId)).containsExactly("null->PENDING");
    }

    /** A cancelled order is finished. Expiring it would overwrite how it actually ended. */
    @Test
    void leavesAnOrderTheMerchantAlreadyCancelled() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);
        cancelOrderService.cancel(merchantId, orderId, "changed my mind");

        sweeperAt(AFTER_EXPIRY).sweep();

        assertThat(statusOf(merchantId, orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(transitions(merchantId)).containsExactly("null->PENDING", "PENDING->CANCELLED");
    }

    // --- THE GUARD (ADR-014) ---------------------------------------------------------------

    /**
     * AN ORDER WITH A LIVE PAYMENT INTENT IS NOT EXPIRED, PROVED WITH A REAL INTENT.
     * <p>
     * This is the test that matters. Expiring it would leave the intent holding the slot of an order
     * that can no longer be paid -- no second intent (the partial unique index refuses it), no
     * confirm and no capture (both re-read payability), no route back through the API. Open item 1's
     * shape, arrived at from the other direction.
     * <p>
     * Unlike the plain-JUnit version, this proves the LOOKUP as well as the rule: the answer comes
     * from {@code PaymentActivityAdapter} delegating to the same {@code existsLiveForOrder} the
     * create path's slot pre-check uses, so "an intent that blocks a second create" and "an intent
     * that blocks an expiry" really are one predicate.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the {@code payments.hasLivePaymentIntent} check
     * from {@code ExpireOrdersService.expireOne}.
     */
    @Test
    void doesNotExpireAnOrderThatStillHasALivePaymentIntent() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);
        createIntentFor(merchantId, orderId);

        sweeperAt(AFTER_EXPIRY).sweep();

        assertThat(statusOf(merchantId, orderId))
            .as("an order being collected against must not be expired out from under the collection")
            .isEqualTo(OrderStatus.PENDING);
        assertThat(transitions(merchantId)).containsExactly("null->PENDING");
        assertThat(eventTypes(merchantId)).containsExactly("order.created", "payment.created");
    }

    /**
     * THE GUARD IS A POSTPONEMENT, NOT A LEAK. Cancel the intent -- which is exactly what releases
     * the slot under ADR-011 -- and the next sweep expires the order. The residue ADR-014 names is
     * "expiry is deferred while a collection is live", and this is that residue resolving itself.
     */
    @Test
    void expiresTheOrderOnceItsPaymentIntentIsCancelled() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);
        PaymentIntent intent = createIntentFor(merchantId, orderId);

        sweeperAt(AFTER_EXPIRY).sweep();

        assertThat(statusOf(merchantId, orderId)).isEqualTo(OrderStatus.PENDING);

        cancelPaymentIntentService.cancel(merchantId, intent.paymentIntentId(), "abandoned");

        assertThat(payments.hasLivePaymentIntent(merchantId, orderId.value()))
            .as("CANCELLED is one of the two statuses that release the slot")
            .isFalse();
        sweeperAt(AFTER_EXPIRY).sweep();

        assertThat(statusOf(merchantId, orderId)).isEqualTo(OrderStatus.EXPIRED);
    }

    /**
     * THE OTHER HALF OF THE GUARD: once expired, the order cannot acquire a payment intent. Both
     * directions have to hold or the guard only moves the problem -- an EXPIRED order that could
     * still be paid would be worse than the stranded intent it was protecting.
     */
    @Test
    void refusesToCreateAPaymentIntentAgainstAnExpiredOrder() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);

        sweeperAt(AFTER_EXPIRY).sweep();

        assertThatThrownBy(() -> createIntentFor(merchantId, orderId))
            .isInstanceOf(com.paymesh.payment.application.OrderNotPayableException.class);
    }

    // --- idempotency ------------------------------------------------------------------------

    /**
     * RUNNING TWICE WRITES NOTHING TWICE, and the row counts are the assertion. The status is EXPIRED
     * either way; a sweeper that appended a second timeline row and a second {@code order.expired}
     * would still leave a correct-looking order behind while telling every consumer the transition
     * happened twice.
     * <p>
     * <b>Sabotage that must turn this red:</b> remove the {@code order.hasExpiredBy(now)} re-check
     * from {@code expireOne}, and widen {@code SpringDataOrderRepository.findExpirable} to drop its
     * {@code status = 'PENDING'} predicate. Either alone is caught; both together is the honest
     * "non-idempotent sweeper".
     */
    @Test
    void writesNothingASecondTimeWhenTheSweepRunsTwice() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);

        ExpireOrdersService sweeper = sweeperAt(AFTER_EXPIRY);

        sweeper.sweep();
        sweeper.sweep();

        assertThat(statusOf(merchantId, orderId)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(transitions(merchantId))
            .as("one transition happened, so one row records it")
            .containsExactly("null->PENDING", "PENDING->EXPIRED");
        assertThat(eventTypes(merchantId))
            .as("and it is announced exactly once")
            .containsExactly("order.created", "order.expired");
    }

    // --- tenancy ----------------------------------------------------------------------------

    /**
     * TENANT-AGNOSTIC SWEEPING, TENANT-SAFE WRITING. The sweep must cross merchants -- a per-merchant
     * job would never run for a merchant nobody called an endpoint for -- but every row it writes
     * carries that row's own merchant, and a merchant with nothing expirable comes out untouched.
     * <p>
     * Proved against the real query, which has no {@code merchant_id} predicate at all. A tenant
     * predicate accidentally added to {@code findExpirable} would fail the first assertion; a
     * mix-up in which merchant a row is written under would fail the third.
     */
    @Test
    void sweepsAcrossMerchantsWithoutTouchingOneThatHasNothingExpirable() {
        MerchantId sweeping = existingMerchant();
        MerchantId bystander = existingMerchant();
        OrderId expiring = expiringOrder(sweeping);
        OrderId live = orderExpiringAt(bystander, AFTER_EXPIRY.plusSeconds(3600));

        sweeperAt(AFTER_EXPIRY).sweep();

        assertThat(statusOf(sweeping, expiring))
            .as("the sweep is not scoped to any one merchant")
            .isEqualTo(OrderStatus.EXPIRED);
        assertThat(statusOf(bystander, live))
            .as("the other merchant's live order is not collateral")
            .isEqualTo(OrderStatus.PENDING);
        assertThat(transitions(bystander))
            .as("and nothing was written under its merchant")
            .containsExactly("null->PENDING");
    }

    /**
     * The history row's tenant is the ORDER's, not any caller's, and the composite foreign key is
     * what enforces it. A row claiming another merchant's order is refused by
     * {@code fk_order_state_history_order} with no application code in the path -- the same shape as
     * the cross-tenant order insert V5 proved.
     */
    @Test
    void refusesARawHistoryRowNamingAnotherMerchantsOrder() {
        MerchantId owner = existingMerchant();
        MerchantId outsider = existingMerchant();
        OrderId orderId = expiringOrder(owner);

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO order_state_history (
                merchant_id, order_id, from_status, to_status, actor_type, occurred_at
            ) VALUES (?, ?, 'PENDING', 'EXPIRED', 'SYSTEM', ?)
            """, outsider.value(), orderId.value(), java.sql.Timestamp.from(AFTER_EXPIRY)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
            .hasMessageContaining("fk_order_state_history_order");
    }

    /** The CHECK admits MERCHANT and SYSTEM only. PROVIDER never touches an order (design spec 0.5). */
    @Test
    void refusesAHistoryRowClaimingAProviderMovedAnOrder() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO order_state_history (
                merchant_id, order_id, from_status, to_status, actor_type, occurred_at
            ) VALUES (?, ?, 'PENDING', 'PAID', 'PROVIDER', ?)
            """, merchantId.value(), orderId.value(), java.sql.Timestamp.from(AFTER_EXPIRY)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
            .hasMessageContaining("ck_order_state_history_actor");
    }

    // --- one bad row ----------------------------------------------------------------------------

    /**
     * ONE UNMAPPABLE ROW COSTS ONE ORDER, NOT THE SWEEP. Open item 2, and it was not latent.
     *
     * <p>The item was filed as "needing database state the current CHECKs forbid". It needs one
     * identifier nobody constrained: {@code merchants.merchant_id} and {@code orders.order_id} are
     * {@code VARCHAR(40)} with no format CHECK, while {@code MerchantId.from} and
     * {@code OrderId.from} refuse anything that is not {@code prefix_uuid}. PostgreSQL accepts the
     * row below today.
     *
     * <p>Before the fix the candidate query built an {@code Order} per row, <b>outside</b> the
     * per-item try/catch. This row has the oldest deadline, so it led every batch, threw out of
     * {@code sweep()} before a single order was examined, and the scheduler re-ran it forever --
     * order expiry silently dead platform-wide, with one WARN per pass and no failing test.
     *
     * <p><b>Sabotage that must turn this red:</b> make {@code findExpirable} return mapped
     * aggregates again, or move the {@code MerchantId.from} / {@code OrderId.from} calls out of
     * {@code ExpireOrdersService}'s try and back into {@code JpaOrderRepository}.
     */
    @Test
    void expiresTheGoodOrdersEvenWhenACandidateRowCannotBeMapped() {
        MerchantId healthy = existingMerchant();
        OrderId healthyOrder = expiringOrder(healthy);

        unmappableExpiringOrder();

        ExpireOrdersService.SweepResult result = sweeperAt(AFTER_EXPIRY).sweep();

        // AT LEAST, never exactly: the sweep is platform-wide, so it also picks up whatever other
        // tests in this class left expirable. See the class javadoc -- "expired exactly 1" would
        // pass or fail on test order rather than on behaviour.
        assertThat(result.failed())
            .as("the bad row is counted as a failure, not thrown out of the sweep")
            .isGreaterThanOrEqualTo(1);
        assertThat(result.expired())
            .as("and the sweep still did work after meeting it")
            .isGreaterThanOrEqualTo(1);

        assertThat(statusOf(healthy, healthyOrder))
            .as("THE POINT: the order behind the bad row was still expired")
            .isEqualTo(OrderStatus.EXPIRED);
    }

    /**
     * A row no mapper can rehydrate, inserted the only way one can exist: straight through JDBC,
     * with an id the application would never mint. Its deadline is the oldest in the table, so it
     * sorts to the head of the candidate batch -- which is what made the original bug total rather
     * than partial.
     */
    private void unmappableExpiringOrder() {
        // Unique, because merchant_id is the primary key and another test in another class plants
        // one of these too. Malformed either way: MerchantId.from wants "mrc_" + a bare UUID.
        // "bad-" + UUID is exactly 40 characters, which is the column width.
        String malformedMerchantId = "bad-" + UUID.randomUUID();

        jdbc.update("""
            insert into merchants
                (merchant_id, business_name, email, country, default_currency, status,
                 created_at, updated_at)
            values (?, 'Corrupt Co', ?, 'IN', 'INR', 'ACTIVE', ?, ?)
            """, malformedMerchantId, UUID.randomUUID() + "@paymesh.test",
            Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));

        jdbc.update("""
            insert into orders
                (order_id, merchant_id, amount_minor, amount_paid_minor, currency, status,
                 metadata, version, expires_at, created_at, updated_at)
            values (?, ?, ?, 0, 'INR', 'PENDING', '{}'::jsonb, 0, ?, ?, ?)
            """,
            "ord_" + UUID.randomUUID(), malformedMerchantId, ORDER_AMOUNT_MINOR,
            Timestamp.from(EXPIRES_AT.minus(Duration.ofDays(1))),
            Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
    }

    // --- the transaction boundary --------------------------------------------------------------

    /**
     * THE TRANSACTION BOUNDARY, FROM THE FAR END. The order is moved and its timeline row written
     * when the outbox append throws. If the three are not in one transaction they commit, and this
     * test finds an EXPIRED order that nothing ever announced.
     * <p>
     * A happy-path test cannot tell three transactions from one.
     */
    @Test
    void leavesTheOrderPendingWhenTheOutboxAppendFails() {
        MerchantId merchantId = existingMerchant();
        OrderId orderId = expiringOrder(merchantId);

        ExpireOrdersService sabotaged = new ExpireOrdersService(
            orders,
            history,
            getOrderService,
            payments,
            event -> {
                throw new IllegalStateException("outbox is down");
            },
            transactionTemplate,
            Clock.fixed(AFTER_EXPIRY, ZoneOffset.UTC),
            200
        );

        SweepResult result = sabotaged.sweep();

        assertThat(result.failed())
            .as("the failure is counted and logged, not swallowed and not fatal to the sweep")
            .isPositive();
        assertThat(statusOf(merchantId, orderId))
            .as("no order may be expired without its event")
            .isEqualTo(OrderStatus.PENDING);
        assertThat(transitions(merchantId)).containsExactly("null->PENDING");
    }

    // --- helpers -----------------------------------------------------------------------------

    /** The production service with the clock moved, which is the only way to make time pass here. */
    private ExpireOrdersService sweeperAt(Instant now) {
        return new ExpireOrdersService(
            orders,
            history,
            getOrderService,
            payments,
            outbox,
            transactionTemplate,
            Clock.fixed(now, ZoneOffset.UTC),
            200
        );
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();
    }

    /** Created through the service, so its {@code null -> PENDING} history row exists too. */
    private OrderId expiringOrder(MerchantId merchantId) {
        return orderExpiringAt(merchantId, EXPIRES_AT);
    }

    /**
     * Always through the service, never {@code orders.save} directly. Two reasons: the creation row
     * in {@code order_state_history} only exists if the service wrote it, so a timeline assertion
     * against a hand-saved order would be asserting an absence the production path does not have --
     * and {@code Order.create} refuses an expiry that is not after {@code createdAt}, which a fixed
     * literal cannot guarantee against a real clock.
     */
    private OrderId orderExpiringAt(MerchantId merchantId, Instant expiresAt) {
        return createOrderService.create(new CreateOrderCommand(
            merchantId, null, null, ORDER_AMOUNT_MINOR, "INR", null, Map.of(), expiresAt
        )).orderId();
    }

    private PaymentIntent createIntentFor(MerchantId merchantId, OrderId orderId) {
        return createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, orderId.value(), null, ORDER_AMOUNT_MINOR, "INR", null, null, Map.of()
        ));
    }

    private OrderStatus statusOf(MerchantId merchantId, OrderId orderId) {
        return getOrderService.getById(merchantId, orderId).status();
    }

    /** The order's transitions as {@code FROM->TO}, with the creation row's null spelled out. */
    private List<String> transitions(MerchantId merchantId) {
        return jdbc.queryForList("""
            select coalesce(from_status, 'null') || '->' || to_status as move
              from order_state_history
             where merchant_id = ?
             order by occurred_at, order_state_history_id
            """, String.class, merchantId.value());
    }

    private List<String> actorsOf(MerchantId merchantId) {
        return jdbc.queryForList("""
            select actor_type from order_state_history
             where merchant_id = ?
             order by occurred_at, order_state_history_id
            """, String.class, merchantId.value());
    }

    private List<String> eventTypes(MerchantId merchantId) {
        return jdbc.queryForList(
            "select event_type from outbox_events where merchant_id = ? order by occurred_at, event_id",
            String.class,
            merchantId.value()
        );
    }
}
