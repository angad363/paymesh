package com.paymesh.payment.application;

import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreatePaymentIntentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");
    private static final String ORDER_ID = "ord_11111111-1111-4111-8111-111111111111";
    private static final String CUSTOMER_ID = "cus_22222222-2222-4222-8222-222222222222";

    private final InMemoryPaymentIntentRepository repository = new InMemoryPaymentIntentRepository();
    private final RecordingHistory history = new RecordingHistory();
    private final KnownOrders orders = new KnownOrders();
    private final ImmediateTransactions transactions = new ImmediateTransactions();
    private final RecordingOutbox outbox = new RecordingOutbox(transactions);
    private final CreatePaymentIntentService service = new CreatePaymentIntentService(
        repository, history, orders, outbox, transactions, Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void createsAnIntentAwaitingAPaymentMethod() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        PaymentIntent intent = service.create(command(merchantId, ORDER_ID, null, 1999, "inr", null));

        assertEquals(merchantId, intent.merchantId());
        assertEquals(ORDER_ID, intent.orderId());
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, intent.status());
        assertEquals(NOW, intent.createdAt());
        assertTrue(intent.paymentIntentId().value().startsWith("pi_"));
    }

    @Test
    void rejectsNullCommand() {
        assertThrows(IllegalArgumentException.class, () -> service.create(null));
    }

    // --- the order link --------------------------------------------------------

    @Test
    void refusesAnOrderThatDoesNotExist() {
        assertThrows(
            OrderNotPayableException.class,
            () -> service.create(command(MerchantId.generate(), ORDER_ID, null, 1999, "INR", null))
        );
    }

    @Test
    void refusesAnOrderThatIsNotPayable() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", false);

        assertThrows(
            OrderNotPayableException.class,
            () -> service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null))
        );
    }

    /**
     * THE CROSS-TENANT CASE. The order is real, it just belongs to someone else, and the caller must
     * not be able to tell that apart from an order that never existed or one that cannot be paid --
     * so all three produce the same exception with the same message.
     */
    @Test
    void refusesAnotherMerchantsOrderIndistinguishablyFromOneThatDoesNotExist() {
        MerchantId owner = MerchantId.generate();
        MerchantId outsider = MerchantId.generate();
        orders.add(owner, ORDER_ID, null, 1999, "INR", true);
        orders.add(outsider, "ord_33333333-3333-4333-8333-333333333333", null, 1999, "INR", false);

        String forStranger = assertThrows(
            OrderNotPayableException.class,
            () -> service.create(command(outsider, ORDER_ID, null, 1999, "INR", null))
        ).getMessage();

        String forUnpayable = assertThrows(
            OrderNotPayableException.class,
            () -> service.create(command(
                outsider, "ord_33333333-3333-4333-8333-333333333333", null, 1999, "INR", null
            ))
        ).getMessage();

        assertEquals(
            forUnpayable.replace("ord_33333333-3333-4333-8333-333333333333", "X"),
            forStranger.replace(ORDER_ID, "X")
        );
    }

    // --- the amount rule -------------------------------------------------------

    @Test
    void refusesAnAmountThatIsNotTheOrders() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        assertThrows(
            PaymentAmountMismatchException.class,
            () -> service.create(command(merchantId, ORDER_ID, null, 500, "INR", null))
        );
    }

    /** Underpaying is refused for the same reason overpaying is: the intent collects the exact obligation. */
    @Test
    void refusesAnAmountBelowTheOrders() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        assertThrows(
            PaymentAmountMismatchException.class,
            () -> service.create(command(merchantId, ORDER_ID, null, 1998, "INR", null))
        );
    }

    @Test
    void refusesACurrencyThatIsNotTheOrders() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        assertThrows(
            PaymentAmountMismatchException.class,
            () -> service.create(command(merchantId, ORDER_ID, null, 1999, "USD", null))
        );
    }

    /**
     * The comparison runs against the NORMALIZED currency, or "inr" would be refused as a mismatch
     * against an order the domain would have stored as "INR" anyway.
     */
    @Test
    void comparesTheCurrencyAfterNormalizing() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        assertEquals(
            "INR",
            service.create(command(merchantId, ORDER_ID, null, 1999, "  inr  ", null)).currency()
        );
    }

    // --- the customer link -----------------------------------------------------

    @Test
    void copiesTheOrdersCustomerOntoTheIntent() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, CUSTOMER_ID, 1999, "INR", true);

        assertEquals(
            CUSTOMER_ID,
            service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null)).customerId()
        );
    }

    @Test
    void acceptsTheCustomerTheOrderAlreadyNames() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, CUSTOMER_ID, 1999, "INR", true);

        assertEquals(
            CUSTOMER_ID,
            service.create(command(merchantId, ORDER_ID, CUSTOMER_ID, 1999, "INR", null)).customerId()
        );
    }

    /**
     * Naming a different customer is a contradiction, not a preference. Left to the database it
     * would surface as a foreign-key failure and a 500.
     */
    @Test
    void refusesACustomerThatIsNotTheOrders() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, CUSTOMER_ID, 1999, "INR", true);

        assertThrows(IllegalArgumentException.class, () -> service.create(command(
            merchantId, ORDER_ID, "cus_44444444-4444-4444-8444-444444444444", 1999, "INR", null
        )));
    }

    @Test
    void leavesAGuestIntentWithoutACustomer() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        assertNull(service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null)).customerId());
    }

    // --- one live intent per order ---------------------------------------------

    @Test
    void refusesASecondLiveIntentForTheSameOrder() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);
        service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null));

        assertThrows(
            OrderHasActivePaymentIntentException.class,
            () -> service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null))
        );
    }

    /** Cancelling releases the slot -- otherwise a stuck intent would be a dead order. */
    @Test
    void allowsASecondIntentOnceTheFirstIsCancelled() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        PaymentIntent first = service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null));
        repository.save(first.cancel(null, NOW));

        assertEquals(
            PaymentIntentStatus.REQUIRES_PAYMENT_METHOD,
            service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null)).status()
        );
    }

    // --- the transaction, the timeline and the event ---------------------------

    /**
     * The intent, its first history row and its event have to be written INSIDE the template's
     * callback, not merely somewhere in the method. A service that wrote all three outside the wrap
     * would still produce all three here, so the recorded flags -- not the counts -- are what this
     * test is for. The durable version of the same claim is PaymentIntentIntegrationTest, against a
     * real transaction.
     */
    @Test
    void writesTheIntentItsTimelineAndItsEventInOneTransaction() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null));

        assertEquals(1, transactions.executions());
        assertEquals(1, outbox.events().size());
        assertEquals(1, history.changes().size());
        assertTrue(outbox.appendedInsideATransaction());
        assertTrue(history.appendedInsideATransaction());
    }

    /** Creation came from nowhere, so its history row has no from-status. */
    @Test
    void opensTheTimelineWithANullFromStatus() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        PaymentIntent intent = service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null));
        PaymentStateChange change = history.changes().get(0);

        assertNull(change.fromStatus());
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, change.toStatus());
        assertEquals(PaymentStateChange.ActorType.MERCHANT, change.actorType());
        assertEquals(merchantId.value(), change.actorId());
        assertEquals(intent.paymentIntentId(), change.paymentIntentId());
        assertEquals(NOW, change.occurredAt());
    }

    @Test
    void carriesTheIntentInTheEventPayload() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, CUSTOMER_ID, 1999, "INR", true);

        PaymentIntent intent = service.create(
            command(merchantId, ORDER_ID, null, 1999, "INR", CaptureMethod.MANUAL)
        );

        OutboxEvent event = outbox.events().get(0);

        assertEquals("payment.created", event.eventType());
        assertEquals("PAYMENT_INTENT", event.aggregateType());
        assertEquals(intent.paymentIntentId().value(), event.aggregateId());
        assertEquals(merchantId, event.merchantId());
        assertEquals(1, event.eventVersion());
        assertEquals(NOW, event.occurredAt());
        assertTrue(event.eventId().value().startsWith("evt_"));

        Map<String, Object> payload = event.payload();

        assertEquals(intent.paymentIntentId().value(), payload.get("paymentIntentId"));
        assertEquals(merchantId.value(), payload.get("merchantId"));
        assertEquals(ORDER_ID, payload.get("orderId"));
        assertEquals(CUSTOMER_ID, payload.get("customerId"));
        assertEquals(1999L, payload.get("amountMinor"));
        assertEquals("INR", payload.get("currency"));
        assertEquals("MANUAL", payload.get("captureMethod"));
        assertEquals("REQUIRES_PAYMENT_METHOD", payload.get("status"));
        assertEquals(NOW.toString(), payload.get("createdAt"));
    }

    /** A guest intent still carries the key, as an explicit null, so every event has one shape. */
    @Test
    void carriesAnAbsentCustomerAsAnExplicitNull() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        service.create(command(merchantId, ORDER_ID, null, 1999, "INR", null));

        Map<String, Object> payload = outbox.events().get(0).payload();

        assertTrue(payload.containsKey("customerId"));
        assertNull(payload.get("customerId"));
    }

    /** A rejected create announces nothing, and never opens a transaction to announce it in. */
    @Test
    void announcesNothingWhenTheIntentIsRefused() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        assertThrows(
            PaymentAmountMismatchException.class,
            () -> service.create(command(merchantId, ORDER_ID, null, 500, "INR", null))
        );

        assertEquals(0, outbox.events().size());
        assertEquals(0, history.changes().size());
        assertEquals(0, transactions.executions());
    }

    private static CreatePaymentIntentCommand command(
        MerchantId merchantId,
        String orderId,
        String customerId,
        long amountMinor,
        String currency,
        CaptureMethod captureMethod
    ) {
        return new CreatePaymentIntentCommand(
            merchantId, orderId, customerId, amountMinor, currency, captureMethod, null, Map.of()
        );
    }

    /**
     * Runs the callback straight through and counts the calls. It cannot roll anything back -- a
     * plain JUnit test has no database to roll back -- so it proves the boundary was *entered*, not
     * that it holds. Proving it holds needs PostgreSQL.
     */
    private static final class ImmediateTransactions extends TransactionTemplate {

        private int executions;
        private boolean inside;

        int executions() {
            return executions;
        }

        boolean inside() {
            return inside;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;
            inside = true;

            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
                inside = false;
            }
        }
    }

    private static final class RecordingOutbox implements OutboxWriter {

        private final ImmediateTransactions transactions;
        private final List<OutboxEvent> events = new ArrayList<>();
        private boolean appendedInsideATransaction;

        private RecordingOutbox(ImmediateTransactions transactions) {
            this.transactions = transactions;
        }

        List<OutboxEvent> events() {
            return events;
        }

        boolean appendedInsideATransaction() {
            return appendedInsideATransaction;
        }

        @Override
        public void append(OutboxEvent event) {
            appendedInsideATransaction = transactions.inside();
            events.add(event);
        }
    }

    private final class RecordingHistory implements PaymentStateHistoryRepository {

        private final List<PaymentStateChange> changes = new ArrayList<>();
        private boolean appendedInsideATransaction;

        List<PaymentStateChange> changes() {
            return changes;
        }

        boolean appendedInsideATransaction() {
            return appendedInsideATransaction;
        }

        @Override
        public void append(PaymentStateChange change) {
            appendedInsideATransaction = transactions.inside();
            changes.add(change);
        }
    }

    /** Stands in for the order module across the port. */
    private static final class KnownOrders implements OrderLookup {

        private final Map<String, PayableOrder> orders = new HashMap<>();

        void add(
            MerchantId merchantId,
            String orderId,
            String customerId,
            long amountMinor,
            String currency,
            boolean payable
        ) {
            orders.put(
                merchantId.value() + "/" + orderId,
                new PayableOrder(orderId, customerId, amountMinor, currency, payable)
            );
        }

        @Override
        public Optional<PayableOrder> find(MerchantId merchantId, String orderId) {
            return Optional.ofNullable(orders.get(merchantId.value() + "/" + orderId));
        }
    }
}
