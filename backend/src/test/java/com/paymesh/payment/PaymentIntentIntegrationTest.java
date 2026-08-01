package com.paymesh.payment;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.CancelOrderService;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.CancelPaymentIntentService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.OrderHasActivePaymentIntentException;
import com.paymesh.payment.application.OrderLookup;
import com.paymesh.payment.application.OrderNotPayableException;
import com.paymesh.payment.application.PaymentAttemptRepository;
import com.paymesh.payment.application.PaymentIntentCursor;
import com.paymesh.payment.application.PaymentIntentRepository;
import com.paymesh.payment.application.PaymentStateHistoryRepository;
import com.paymesh.payment.domain.PaymentAttemptId;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentNotCancellableException;
import com.paymesh.payment.domain.PaymentIntentNotConfirmableException;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payment intent invariants that only PostgreSQL can prove.
 * <p>
 * Deliberately NOT {@code @Transactional}. A test transaction would wrap every write in an outer
 * transaction that rolls back at the end, and the two claims this class exists for -- that creation's
 * three writes share one transaction, and that a second live intent loses to a unique index -- would
 * then pass whether or not they were true. The concurrency test needs real commits for a second
 * thread to see anything at all. Every test therefore registers its own merchant and scopes its
 * queries to it, so rows surviving between tests cannot make one test's assertions depend on
 * another's.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class PaymentIntentIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30Z");
    private static final long ORDER_AMOUNT_MINOR = 1999;

    /**
     * Every column the raw-JDBC tests below have to name themselves. They bypass the mapper on
     * purpose, so the column list is written out here rather than inferred from the entity -- the
     * whole point of those tests is that no Java code stands between the insert and the constraint.
     */
    private static final String RAW_INSERT = """
        INSERT INTO payment_intents (
            payment_intent_id, merchant_id, order_id, customer_id, amount_minor, currency,
            capture_method, status, captured_amount_minor, refunded_amount_minor,
            version, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, 'INR', 'AUTOMATIC', 'REQUIRES_PAYMENT_METHOD', 0, 0, 0, ?, ?)
        """;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private CancelPaymentIntentService cancelPaymentIntentService;

    @Autowired
    private GetPaymentIntentService getPaymentIntentService;

    @Autowired
    private CancelOrderService cancelOrderService;

    @Autowired
    private PaymentAttemptRepository attempts;

    @Autowired
    private PaymentIntentRepository paymentIntents;

    @Autowired
    private PaymentStateHistoryRepository history;

    @Autowired
    private OrderLookup orderLookup;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private JdbcTemplate jdbc;

    // --- the three writes ------------------------------------------------------

    /**
     * Creation's whole footprint, counted: the intent, the first row of its timeline, and the event
     * announcing it. The counts are as load-bearing as the values -- two history rows for one
     * creation would corrupt an audit trail whose only purpose is to be exact.
     */
    @Test
    void writesTheIntentItsFirstHistoryRowAndItsEventWhenAnIntentIsCreated() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);

        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));

        assertThat(intentCount(merchantId)).isOne();
        assertThat(paymentIntents.findByPaymentIntentId(merchantId, intent.paymentIntentId()))
            .isPresent();

        List<Map<String, Object>> timeline = jdbc.queryForList("""
            select from_status, to_status, actor_type, actor_id, payment_intent_id
              from payment_state_history
             where merchant_id = ?
            """, merchantId.value());

        assertThat(timeline).as("exactly one transition row per creation").hasSize(1);
        assertThat(timeline.get(0).get("from_status"))
            .as("an intent that has just been created came from nowhere")
            .isNull();
        assertThat(timeline.get(0))
            .containsEntry("to_status", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD.name())
            .containsEntry("actor_type", "MERCHANT")
            .containsEntry("actor_id", merchantId.value())
            .containsEntry("payment_intent_id", intent.paymentIntentId().value());

        List<Map<String, Object>> events = jdbc.queryForList("""
            select event_type, aggregate_type, aggregate_id, published_at
              from outbox_events
             where merchant_id = ?
            """, merchantId.value());

        assertThat(events).as("exactly one event per creation").hasSize(1);
        assertThat(events.get(0))
            .containsEntry("event_type", "payment.created")
            .containsEntry("aggregate_type", "PAYMENT_INTENT")
            .containsEntry("aggregate_id", intent.paymentIntentId().value());
        assertThat(events.get(0).get("published_at"))
            .as("nothing publishes yet; a null published_at IS the status model")
            .isNull();
    }

    /**
     * THE TRANSACTION BOUNDARY, from the far end. The intent row and the history row are both
     * flushed before the outbox append runs, so at the moment of failure they genuinely exist in the
     * session. If the three writes are not in one transaction they commit and this test finds them.
     * <p>
     * A happy-path test cannot tell the difference: three separate transactions produce exactly the
     * same rows when nothing fails.
     */
    @Test
    void leavesNoIntentOrHistoryRowBehindWhenTheOutboxAppendFails() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);

        CreatePaymentIntentService sabotaged = serviceWith(paymentIntents, history, event -> {
            throw new IllegalStateException("outbox is down");
        });

        assertThatThrownBy(() -> sabotaged.create(command(merchantId, orderId)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("outbox is down");

        assertThat(intentCount(merchantId)).as("the intent must not survive its lost event").isZero();
        assertThat(historyCount(merchantId)).isZero();
        assertThat(outboxCount(merchantId)).isZero();
    }

    /**
     * The other direction, and the reason it is worth its own test: here the intent row is the one
     * already in the session when the failure lands, so a service that committed the intent before
     * opening a transaction for the rest of the work would be caught by this and not by the test
     * above.
     */
    @Test
    void leavesNoIntentOrEventBehindWhenTheHistoryAppendFails() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);

        CreatePaymentIntentService sabotaged = serviceWith(paymentIntents, change -> {
            throw new IllegalStateException("history is down");
        }, outbox);

        assertThatThrownBy(() -> sabotaged.create(command(merchantId, orderId)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("history is down");

        assertThat(intentCount(merchantId))
            .as("an intent with a hole in its timeline must not exist")
            .isZero();
        assertThat(historyCount(merchantId)).isZero();
        assertThat(outboxCount(merchantId)).isZero();
    }

    // --- one live intent per order ----------------------------------------------

    /**
     * THE MOST IMPORTANT TEST IN THIS CLASS. The service is built with the application pre-check
     * answering "no live intent" for every order, which is exactly what deleting
     * {@code existsLiveForOrder} from {@code CreatePaymentIntentService} would leave behind. The only
     * thing left between the caller and a second live intent is
     * {@code uq_payment_intents_live_per_order}, and the caller still sees the business exception
     * rather than a 500, because the adapter translates the constraint by name.
     * <p>
     * ADR-011 states the rule as a database rule for the reason the next test demonstrates: a
     * pre-check is a check, not a lock, and two concurrent creates can both pass it.
     */
    @Test
    void refusesASecondLiveIntentByDatabaseConstraintWhenThePreCheckIsBypassed() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        CreatePaymentIntentService withoutPreCheck = serviceWithoutThePreCheck();

        withoutPreCheck.create(command(merchantId, orderId));

        assertThatThrownBy(() -> withoutPreCheck.create(command(merchantId, orderId)))
            .isInstanceOf(OrderHasActivePaymentIntentException.class)
            .hasMessageContaining(orderId);

        assertThat(intentCount(merchantId)).as("the loser leaves nothing behind").isOne();
        assertThat(historyCount(merchantId)).isOne();
        assertThat(outboxCount(merchantId)).isOne();
    }

    /**
     * The race the index exists for, run for real. Both callers pass the pre-check -- here because
     * it is bypassed, in production because nothing serializes it -- so both reach the insert.
     * PostgreSQL blocks the second on the index entry until the first commits and then refuses it.
     */
    @Test
    void producesExactlyOneIntentWhenTwoCreatesRaceForOneOrder() throws Exception {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        CreatePaymentIntentService withoutPreCheck = serviceWithoutThePreCheck();

        List<Throwable> failures = inParallel(2, () -> {
            try {
                withoutPreCheck.create(command(merchantId, orderId));
                return null;
            } catch (RuntimeException exception) {
                return exception;
            }
        });

        assertThat(intentCount(merchantId)).as("an order collects once or not at all").isOne();
        assertThat(historyCount(merchantId)).isOne();
        assertThat(outboxCount(merchantId)).isOne();
        assertThat(failures.stream().filter(failure -> failure != null).toList())
            .as("the loser is told why, and it is not a 500")
            .singleElement()
            .isInstanceOf(OrderHasActivePaymentIntentException.class);
    }

    /**
     * Cancelling is what releases the slot. Without this the index would turn any abandoned intent
     * into a dead order -- worse than the overpayment it prevents, which is why every state a
     * customer can strand an intent in has a cancel route (ADR-011).
     */
    @Test
    void allowsASecondIntentOnceTheFirstIsCancelled() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);

        PaymentIntent first = createPaymentIntentService.create(command(merchantId, orderId));
        cancelPaymentIntentService.cancel(merchantId, first.paymentIntentId(), "customer changed mind");

        PaymentIntent second = createPaymentIntentService.create(command(merchantId, orderId));

        assertThat(second.status()).isEqualTo(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(second.paymentIntentId()).isNotEqualTo(first.paymentIntentId());
        assertThat(intentCount(merchantId)).isEqualTo(2);
    }

    /**
     * The exclusion set is exactly FAILED and CANCELLED: an order that has already been paid must
     * not acquire a second intent.
     * <p>
     * SUCCEEDED is not reachable through any code path in this PR -- provider callbacks own it -- so
     * the row is moved there with raw SQL rather than through the aggregate. That also has to
     * satisfy {@code ck_payment_intents_succeeded_captured} and
     * {@code ck_payment_intents_method_known}, which is why the update sets a captured amount and a
     * method type: a SUCCEEDED intent that captured nothing is a contradiction the schema refuses.
     * The pre-check is bypassed so the index, not the application's status list, is what answers.
     */
    @Test
    void refusesASecondIntentWhileTheFirstHasSucceeded() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        CreatePaymentIntentService withoutPreCheck = serviceWithoutThePreCheck();

        PaymentIntent succeeded = withoutPreCheck.create(command(merchantId, orderId));

        jdbc.update("""
            update payment_intents
               set status = 'SUCCEEDED',
                   captured_amount_minor = amount_minor,
                   payment_method_type = 'CARD'
             where payment_intent_id = ?
            """, succeeded.paymentIntentId().value());

        assertThatThrownBy(() -> withoutPreCheck.create(command(merchantId, orderId)))
            .isInstanceOf(OrderHasActivePaymentIntentException.class);

        assertThat(intentCount(merchantId)).isOne();
    }

    // --- tenancy, with no application code in the path ---------------------------

    /**
     * The control. A raw insert naming the merchant's own order and own customer must SUCCEED, or
     * the two refusals below would prove nothing -- a mistyped column list fails every insert, and
     * only this test notices.
     */
    @Test
    void acceptsARawIntentNamingItsOwnMerchantsOrderAndCustomer() {
        MerchantId merchantId = existingMerchant();
        String customerId = existingCustomer(merchantId);
        String orderId = existingOrder(merchantId, customerId);

        assertThat(rawInsert(merchantId, orderId, customerId)).isOne();
    }

    /**
     * THE GUARANTEE, not the check. {@code CreatePaymentIntentService} would have refused this as
     * ORDER_NOT_PAYABLE, but the composite key on (merchant_id, order_id) is what makes it
     * impossible: a write that bypasses the service entirely still cannot collect against another
     * tenant's obligation. A foreign key on order_id alone would have accepted this row.
     */
    @Test
    void refusesARawIntentNamingAnotherMerchantsOrder() {
        MerchantId owner = existingMerchant();
        MerchantId outsider = existingMerchant();
        String orderOfOwner = existingOrder(owner, null);

        assertThatThrownBy(() -> rawInsert(outsider, orderOfOwner, null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_payment_intents_order");

        assertThat(intentCount(outsider)).isZero();
    }

    /** The same reasoning for the customer link, which is nullable but not cross-tenant. */
    @Test
    void refusesARawIntentNamingAnotherMerchantsCustomer() {
        MerchantId owner = existingMerchant();
        MerchantId outsider = existingMerchant();
        String customerOfOwner = existingCustomer(owner);
        String orderOfOutsider = existingOrder(outsider, null);

        assertThatThrownBy(() -> rawInsert(outsider, orderOfOutsider, customerOfOwner))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_payment_intents_customer");

        assertThat(intentCount(outsider)).isZero();
    }

    // --- the timeline ------------------------------------------------------------

    /**
     * One transition, one row -- and this one names where it came from, unlike creation's. Together
     * with the creation row counted above, a cancelled intent's timeline is exactly two rows and
     * neither of them is a guess.
     */
    @Test
    void writesOneFurtherHistoryRowWhenAnIntentIsCancelled() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));

        cancelPaymentIntentService.cancel(merchantId, intent.paymentIntentId(), "out of stock");

        List<Map<String, Object>> timeline = jdbc.queryForList("""
            select from_status, to_status, actor_type, reason
              from payment_state_history
             where merchant_id = ?
             order by occurred_at, payment_state_history_id
            """, merchantId.value());

        assertThat(timeline).as("creation, then the cancellation, and nothing else").hasSize(2);
        assertThat(timeline.get(1))
            .containsEntry("from_status", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD.name())
            .containsEntry("to_status", PaymentIntentStatus.CANCELLED.name())
            .containsEntry("actor_type", "MERCHANT")
            .containsEntry("reason", "out of stock");
    }

    /**
     * CANCELLING IS ANNOUNCED, and the spec did not originally say so.
     * <p>
     * Cancel is the transition that releases the order's live-intent slot (ADR-011). A consumer fed
     * only payment.created would carry a live intent in its read model forever, so the stream would
     * have a hole in exactly the place a reader most needs it. The event is written in the same
     * transaction as the transition, like every other write in this capability.
     */
    @Test
    void announcesTheCancellationAlongsideTheTransition() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));

        cancelPaymentIntentService.cancel(merchantId, intent.paymentIntentId(), "out of stock");

        // The payload fields are extracted in SQL rather than matched as text: JSONB is stored
        // parsed and renders with its own spacing, so a string comparison would be asserting
        // PostgreSQL's formatting rather than the event's content.
        List<Map<String, Object>> events = jdbc.queryForList("""
            select event_type,
                   aggregate_id,
                   published_at,
                   payload ->> 'previousStatus'     as previous_status,
                   payload ->> 'status'             as status,
                   payload ->> 'cancellationReason' as cancellation_reason
              from outbox_events
             where merchant_id = ?
             order by occurred_at, event_id
            """, merchantId.value());

        assertThat(events)
            .as("the creation and the cancellation, one event each")
            .hasSize(2);

        Map<String, Object> cancelled = events.stream()
            .filter(event -> "payment.cancelled".equals(event.get("event_type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payment.cancelled event was written"));

        assertThat(cancelled)
            .containsEntry("aggregate_id", intent.paymentIntentId().value())
            // The state it came from is the part a plain "it is cancelled now" event cannot carry,
            // and a consumer reconciling a timeline needs it to know whether a payment method was
            // ever attached before the intent died.
            .containsEntry("previous_status", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD.name())
            .containsEntry("status", PaymentIntentStatus.CANCELLED.name())
            .containsEntry("cancellation_reason", "out of stock");
        assertThat(cancelled.get("published_at")).isNull();
    }

    // --- attach and confirm -----------------------------------------------------------

    /**
     * Confirm's whole footprint, counted: the attempt, the moved intent, one more timeline row and
     * one more event. Attach's is counted alongside it, so the two transitions together are exactly
     * three history rows and three events and not one more of either.
     */
    @Test
    void writesTheAttemptTheTransitionItsHistoryRowAndItsEventWhenAnIntentIsConfirmed() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));

        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );
        PaymentIntent processing = confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), "https://shop.test/return?session=SECRET", "web"
        ));

        assertThat(processing.status()).isEqualTo(PaymentIntentStatus.PROCESSING);

        List<Map<String, Object>> attempts = jdbc.queryForList("""
            select payment_attempt_id, payment_intent_id, attempt_number, provider, status,
                   amount_minor, currency, provider_reference, response_payload,
                   request_payload ->> 'returnUrl' as return_url,
                   request_payload ->> 'device'    as device
              from payment_attempts
             where merchant_id = ?
            """, merchantId.value());

        assertThat(attempts).as("exactly one attempt per confirmation").hasSize(1);
        assertThat(attempts.get(0))
            .containsEntry("payment_intent_id", intent.paymentIntentId().value())
            .containsEntry("attempt_number", 1)
            .containsEntry("provider", "SIMULATOR")
            .containsEntry("status", "PROCESSING")
            .containsEntry("amount_minor", ORDER_AMOUNT_MINOR)
            .containsEntry("currency", "INR")
            // The query string is gone. It routinely carries a session token, and this row is a
            // durable audit record.
            .containsEntry("return_url", "https://shop.test/return")
            .containsEntry("device", "web");
        assertThat(attempts.get(0).get("provider_reference"))
            .as("nothing has answered, because nothing was called")
            .isNull();
        assertThat(attempts.get(0).get("response_payload")).isNull();
        assertThat(attempts.get(0).get("payment_attempt_id").toString()).startsWith("pat_");

        List<Map<String, Object>> timeline = jdbc.queryForList("""
            select from_status, to_status
              from payment_state_history
             where merchant_id = ?
             order by occurred_at, payment_state_history_id
            """, merchantId.value());

        assertThat(timeline).as("create, attach, confirm -- one row each").hasSize(3);
        assertThat(timeline.get(1))
            .containsEntry("from_status", "REQUIRES_PAYMENT_METHOD")
            .containsEntry("to_status", "REQUIRES_CONFIRMATION");
        assertThat(timeline.get(2))
            .containsEntry("from_status", "REQUIRES_CONFIRMATION")
            .containsEntry("to_status", "PROCESSING");

        assertThat(eventTypes(merchantId))
            .as("one event per transition, in order")
            .containsExactly("payment.created", "payment.method_attached", "payment.processing");
    }

    /**
     * THE TRANSACTION BOUNDARY FOR CONFIRM, from the far end. The attempt row and the moved intent
     * are both flushed before the outbox append runs, so at the moment of failure they genuinely
     * exist in the session. A happy-path test cannot tell four separate transactions from one.
     */
    @Test
    void leavesNoAttemptOrTransitionBehindWhenTheConfirmOutboxAppendFails() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));
        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );

        ConfirmPaymentIntentService sabotaged = new ConfirmPaymentIntentService(
            paymentIntents,
            attempts,
            history,
            getPaymentIntentService,
            orderLookup,
            event -> {
                throw new IllegalStateException("outbox is down");
            },
            transactionTemplate,
            clock
        );

        assertThatThrownBy(() -> sabotaged.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("outbox is down");

        assertThat(attemptCount(merchantId)).as("no collection may start without its event").isZero();
        assertThat(getPaymentIntentService.getById(merchantId, intent.paymentIntentId()).status())
            .as("the intent must not be left believing it is collecting")
            .isEqualTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        // Creation and attach wrote one each; the rolled-back confirm added neither.
        assertThat(historyCount(merchantId)).isEqualTo(2);
        assertThat(eventTypes(merchantId))
            .containsExactly("payment.created", "payment.method_attached");
    }

    /**
     * A DOUBLE-CLICKED CONFIRM, RUN FOR REAL. Exactly one attempt exists afterwards, which is the
     * property that matters: two attempts would be two collections opened against one obligation.
     * <p>
     * WHICH mechanism refuses the loser is worth being precise about, because it changed during this
     * PR. Confirm takes a row lock on the intent, so the second caller waits, re-reads PROCESSING and
     * is refused by the state machine -- a 409 that names the situation. Without the lock the two
     * raced on the intent's optimistic version instead and the loser got an "unexpected row count"
     * 500, nondeterministically: whether it collided on the attempt-number index or on the version
     * depended on which side of the winner's commit its count query landed. A user-facing 500 that
     * only appears under load is exactly the kind of thing that is discovered in production.
     * <p>
     * {@code uq_payment_attempts_intent_number} is now the backstop rather than the arbiter, and it
     * is proved directly by the raw-SQL test below.
     */
    @Test
    void producesExactlyOneAttemptWhenTwoConfirmsRace() throws Exception {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));
        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );

        List<Throwable> failures = inParallel(2, () -> {
            try {
                confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
                    merchantId, intent.paymentIntentId(), null, null
                ));
                return null;
            } catch (RuntimeException exception) {
                return (Throwable) exception;
            }
        });

        assertThat(attemptCount(merchantId)).as("an intent starts collecting once").isOne();
        assertThat(failures.stream().filter(failure -> failure != null).toList())
            .as("the loser is told why, and it is not a 500")
            .singleElement()
            .isInstanceOf(PaymentIntentNotConfirmableException.class);
        assertThat(eventTypes(merchantId))
            .as("the loser announces nothing")
            .containsExactly("payment.created", "payment.method_attached", "payment.processing");
    }

    /**
     * THE BACKSTOP, PROVED DIRECTLY. With confirm serializing on the intent's row lock, no code path
     * can reach a duplicate attempt number any more -- which is exactly when a constraint quietly
     * stops being tested. This insert goes round the application entirely, so what answers is
     * {@code uq_payment_attempts_intent_number} and nothing else.
     * <p>
     * It matters beyond today: the callback PR makes a second attempt legitimate
     * (REQUIRES_ACTION, then confirm again), and the number is only unique if the database says so.
     */
    @Test
    void refusesTwoRawAttemptsSharingAnAttemptNumber() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));

        assertThat(rawAttempt(merchantId, intent.paymentIntentId(), 1)).isOne();
        assertThat(rawAttempt(merchantId, intent.paymentIntentId(), 2))
            .as("a different number is fine -- an intent may be retried")
            .isOne();

        assertThatThrownBy(() -> rawAttempt(merchantId, intent.paymentIntentId(), 2))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uq_payment_attempts_intent_number");

        assertThat(attemptCount(merchantId)).isEqualTo(2);
    }

    /**
     * ADR-013, END TO END AND THROUGH THE REAL ORDER MODULE. The order is cancelled after the intent
     * was created -- which nothing prevents, because Order is not allowed to know Payment exists --
     * and confirm must refuse. Without this check PayMesh would collect for an order the merchant
     * explicitly cancelled, which is the whole reason the guard is here rather than left to review.
     */
    @Test
    void refusesToConfirmAgainstAnOrderCancelledAfterTheIntentWasCreated() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));
        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );

        cancelOrderService.cancel(merchantId, OrderId.from(orderId), "sold out");

        assertThatThrownBy(() -> confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        )))
            .isInstanceOf(OrderNotPayableException.class)
            .hasMessageContaining(orderId);

        assertThat(attemptCount(merchantId)).as("a refused confirm starts nothing").isZero();
        assertThat(getPaymentIntentService.getById(merchantId, intent.paymentIntentId()).status())
            .isEqualTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
    }

    /**
     * The route out of REQUIRES_CONFIRMATION, and the reason ADR-011's table needed a row for it:
     * an abandoned checkout must be able to release the order's only slot, or the order is dead.
     */
    @Test
    void cancelsAnIntentAwaitingConfirmationAndFreesTheOrder() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));
        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.WALLET
        );

        cancelPaymentIntentService.cancel(merchantId, intent.paymentIntentId(), "abandoned");

        assertThat(createPaymentIntentService.create(command(merchantId, orderId)).status())
            .as("the slot is released, so the order can be collected again")
            .isEqualTo(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
    }

    /**
     * PROCESSING IS UNCANCELLABLE AND MUST STAY SO (ADR-011 section 5): an in-flight attempt may
     * already have succeeded at the provider, so cancelling locally could erase a payment that
     * really happened. Asserted here as well as in the domain test because this is the state a real
     * confirm actually produces.
     */
    @Test
    void refusesToCancelAnIntentItHasConfirmed() {
        MerchantId merchantId = existingMerchant();
        String orderId = existingOrder(merchantId, null);
        PaymentIntent intent = createPaymentIntentService.create(command(merchantId, orderId));
        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        assertThatThrownBy(
            () -> cancelPaymentIntentService.cancel(merchantId, intent.paymentIntentId(), "too late")
        ).isInstanceOf(PaymentIntentNotCancellableException.class);

        assertThat(getPaymentIntentService.getById(merchantId, intent.paymentIntentId()).status())
            .isEqualTo(PaymentIntentStatus.PROCESSING);
    }

    /**
     * THE GUARANTEE, not the check, for the attempts table. The composite key on
     * (merchant_id, payment_intent_id) is what stops one merchant's attempt hanging off another
     * merchant's intent; a foreign key on payment_intent_id alone would have accepted this row.
     */
    @Test
    void refusesARawAttemptNamingAnotherMerchantsIntent() {
        MerchantId owner = existingMerchant();
        MerchantId outsider = existingMerchant();
        PaymentIntent intentOfOwner = createPaymentIntentService.create(
            command(owner, existingOrder(owner, null))
        );

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO payment_attempts (
                payment_attempt_id, merchant_id, payment_intent_id, attempt_number, provider,
                status, amount_minor, currency, version, created_at, updated_at
            ) VALUES (?, ?, ?, 1, 'SIMULATOR', 'PROCESSING', ?, 'INR', 0, ?, ?)
            """,
            PaymentAttemptId.generate().value(),
            outsider.value(),
            intentOfOwner.paymentIntentId().value(),
            ORDER_AMOUNT_MINOR,
            Timestamp.from(CREATED_AT),
            Timestamp.from(CREATED_AT)
        ))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_payment_attempts_intent");

        assertThat(attemptCount(outsider)).isZero();
    }

    // --- paging --------------------------------------------------------------------

    /**
     * THE BOUNDARY THE TIEBREAK EXISTS FOR, against the real SQL.
     * <p>
     * Three intents stamped with the SAME created_at -- which is why the application supplies time
     * from an injectable Clock rather than letting the database default it -- paged two at a time, so
     * the page boundary falls between rows a timestamp alone cannot order. Each needs its own order,
     * because one order holds one live intent.
     * <p>
     * Without payment_intent_id in both the ORDER BY and the cursor predicate this fails, and it
     * fails silently: a strict {@code created_at <} walks past every row sharing the boundary instant
     * and the third intent is never returned, while {@code <=} hands the first two out twice. Counting
     * what came back across all pages is the only thing that notices.
     */
    @Test
    void pagesAcrossABoundaryWhereIntentsShareACreatedAt() {
        MerchantId merchantId = existingMerchant();
        Set<PaymentIntentId> created = new HashSet<>();

        for (int index = 0; index < 3; index++) {
            created.add(paymentIntents.save(PaymentIntent.create(
                PaymentIntentId.generate(),
                merchantId,
                existingOrder(merchantId, null),
                null,
                ORDER_AMOUNT_MINOR,
                "INR",
                null,
                null,
                Map.of(),
                CREATED_AT
            )).paymentIntentId());
        }

        List<PaymentIntentId> seen = new ArrayList<>();
        PaymentIntentCursor cursor = PaymentIntentCursor.start();

        while (true) {
            List<PaymentIntent> page = paymentIntents.findPage(merchantId, null, null, cursor, 2);

            if (page.isEmpty()) {
                break;
            }

            page.forEach(intent -> seen.add(intent.paymentIntentId()));
            PaymentIntent last = page.get(page.size() - 1);
            cursor = PaymentIntentCursor.of(last.createdAt(), last.paymentIntentId().value());
        }

        assertThat(seen).as("every intent exactly once, none skipped, none repeated")
            .hasSize(3)
            .containsExactlyInAnyOrderElementsOf(created);
    }

    // --- helpers ---------------------------------------------------------------------

    /**
     * The production service with one collaborator swapped out, so a single write can be made to
     * fail while the others are genuinely in the session.
     */
    private CreatePaymentIntentService serviceWith(
        PaymentIntentRepository repository,
        PaymentStateHistoryRepository timeline,
        OutboxWriter events
    ) {
        return new CreatePaymentIntentService(
            repository, timeline, orderLookup, events, transactionTemplate, clock
        );
    }

    /**
     * The service as it would be if {@code existsLiveForOrder} were deleted from the create path:
     * the pre-check always answers "no live intent", so only the database can refuse the second one.
     */
    private CreatePaymentIntentService serviceWithoutThePreCheck() {
        return serviceWith(new AlwaysFreeSlot(paymentIntents), history, outbox);
    }

    private static <T> List<T> inParallel(int callers, Callable<T> call) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(callers);

        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Future<T>> futures = new ArrayList<>();

            for (int caller = 0; caller < callers; caller++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    return call.call();
                }));
            }

            List<T> results = new ArrayList<>();

            for (Future<T> future : futures) {
                results.add(future.get());
            }

            return results;
        }
    }

    private static CreatePaymentIntentCommand command(MerchantId merchantId, String orderId) {
        return new CreatePaymentIntentCommand(
            merchantId, orderId, null, ORDER_AMOUNT_MINOR, "INR", null, null, Map.of()
        );
    }

    private int rawInsert(MerchantId merchantId, String orderId, String customerId) {
        return jdbc.update(
            RAW_INSERT,
            PaymentIntentId.generate().value(),
            merchantId.value(),
            orderId,
            customerId,
            ORDER_AMOUNT_MINOR,
            Timestamp.from(CREATED_AT),
            Timestamp.from(CREATED_AT)
        );
    }

    private long intentCount(MerchantId merchantId) {
        return count("select count(*) from payment_intents where merchant_id = ?", merchantId);
    }

    /** An attempt written with no Java between the insert and the constraints. */
    private int rawAttempt(MerchantId merchantId, PaymentIntentId intentId, int attemptNumber) {
        return jdbc.update("""
            INSERT INTO payment_attempts (
                payment_attempt_id, merchant_id, payment_intent_id, attempt_number, provider,
                status, amount_minor, currency, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'SIMULATOR', 'PROCESSING', ?, 'INR', 0, ?, ?)
            """,
            PaymentAttemptId.generate().value(),
            merchantId.value(),
            intentId.value(),
            attemptNumber,
            ORDER_AMOUNT_MINOR,
            Timestamp.from(CREATED_AT),
            Timestamp.from(CREATED_AT)
        );
    }

    private long attemptCount(MerchantId merchantId) {
        return count("select count(*) from payment_attempts where merchant_id = ?", merchantId);
    }

    /** Every event this merchant has, oldest first: the counts and the order are both assertions. */
    private List<String> eventTypes(MerchantId merchantId) {
        return jdbc.queryForList(
            "select event_type from outbox_events where merchant_id = ? order by occurred_at, event_id",
            String.class,
            merchantId.value()
        );
    }

    private long historyCount(MerchantId merchantId) {
        return count("select count(*) from payment_state_history where merchant_id = ?", merchantId);
    }

    private long outboxCount(MerchantId merchantId) {
        return count(
            "select count(*) from outbox_events where merchant_id = ? and event_type = 'payment.created'",
            merchantId
        );
    }

    private long count(String sql, MerchantId merchantId) {
        return jdbc.queryForObject(sql, Long.class, merchantId.value());
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();
    }

    private String existingOrder(MerchantId merchantId, String customerId) {
        return orders.save(Order.create(
            OrderId.generate(),
            merchantId,
            customerId,
            null,
            ORDER_AMOUNT_MINOR,
            "INR",
            null,
            Map.of(),
            null,
            CREATED_AT
        )).orderId().value();
    }

    private String existingCustomer(MerchantId merchantId) {
        return customers.save(Customer.create(
            CustomerId.generate(),
            merchantId,
            null,
            UUID.randomUUID() + "@buyer.test",
            "Asha Rao",
            null,
            CREATED_AT
        )).customerId().value();
    }

    /**
     * The real adapter with its pre-check answering "free" for every order. Everything else is
     * delegated, so the insert, the constraint translation and the paging under test are the
     * production ones.
     */
    private record AlwaysFreeSlot(PaymentIntentRepository delegate) implements PaymentIntentRepository {

        @Override
        public boolean existsLiveForOrder(MerchantId merchantId, String orderId) {
            return false;
        }

        @Override
        public PaymentIntent save(PaymentIntent paymentIntent) {
            return delegate.save(paymentIntent);
        }

        @Override
        public Optional<PaymentIntent> findByPaymentIntentId(
            MerchantId merchantId,
            PaymentIntentId paymentIntentId
        ) {
            return delegate.findByPaymentIntentId(merchantId, paymentIntentId);
        }

        @Override
        public Optional<PaymentIntent> findByPaymentIntentIdForUpdate(
            MerchantId merchantId,
            PaymentIntentId paymentIntentId
        ) {
            return delegate.findByPaymentIntentIdForUpdate(merchantId, paymentIntentId);
        }

        @Override
        public Optional<PaymentIntent> findForProviderCallbackForUpdate(
            PaymentIntentId paymentIntentId
        ) {
            return delegate.findForProviderCallbackForUpdate(paymentIntentId);
        }

        @Override
        public List<PaymentIntent> findPage(
            MerchantId merchantId,
            PaymentIntentStatus status,
            String orderId,
            PaymentIntentCursor cursor,
            int limit
        ) {
            return delegate.findPage(merchantId, status, orderId, cursor, limit);
        }

        @Override
        public List<PaymentIntent> findStrandedInProcessing(Instant confirmedBefore, int limit) {
            return delegate.findStrandedInProcessing(confirmedBefore, limit);
        }
    }
}
