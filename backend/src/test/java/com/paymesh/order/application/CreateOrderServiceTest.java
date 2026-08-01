package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderStatus;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T10:15:30Z");

    private final OrderRepository repository = new InMemoryOrderRepository();
    private final KnownCustomers customers = new KnownCustomers();
    private final ImmediateTransactions transactions = new ImmediateTransactions();
    private final RecordingOutbox outbox = new RecordingOutbox(transactions);
    private final CreateOrderService service = new CreateOrderService(
        repository, customers, outbox, transactions, Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void createsAPendingOrderStampedWithTheInjectedClock() {
        MerchantId merchantId = MerchantId.generate();

        Order order = service.create(command(merchantId, null, null, 1999, "inr"));

        assertEquals(merchantId, order.merchantId());
        assertEquals(1999L, order.amountMinor());
        assertEquals("INR", order.currency());
        assertEquals(0L, order.amountPaidMinor());
        assertEquals(OrderStatus.PENDING, order.status());
        assertEquals(NOW, order.createdAt());
        assertTrue(order.orderId().value().startsWith("ord_"));
    }

    @Test
    void mintsADistinctIdentifierPerOrder() {
        MerchantId merchantId = MerchantId.generate();

        assertNotEquals(
            service.create(command(merchantId, null, null, 1999, "INR")).orderId(),
            service.create(command(merchantId, null, null, 1999, "INR")).orderId()
        );
    }

    @Test
    void rejectsAZeroAmount() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.create(command(MerchantId.generate(), null, null, 0, "INR"))
        );
    }

    @Test
    void rejectsNullCommand() {
        assertThrows(IllegalArgumentException.class, () -> service.create(null));
    }

    // --- the order.created event ----------------------------------------------

    /**
     * The append has to happen INSIDE the template's callback, not merely somewhere in the method.
     * A service that saved and appended outside the wrap would still produce both rows here, so the
     * recorded flag -- not the event count -- is what this test is for. The durable version of the
     * same claim is OutboxTransactionIntegrationTest, against a real transaction.
     */
    @Test
    void appendsOrderCreatedInsideTheTransactionThatSavedTheOrder() {
        Order order = service.create(command(MerchantId.generate(), null, null, 1999, "INR"));

        assertEquals(1, transactions.executions());
        assertEquals(1, outbox.events().size());
        assertTrue(outbox.appendedInsideATransaction());

        OutboxEvent event = outbox.events().get(0);

        assertEquals("order.created", event.eventType());
        assertEquals("ORDER", event.aggregateType());
        assertEquals(order.orderId().value(), event.aggregateId());
        assertEquals(order.merchantId(), event.merchantId());
        assertEquals(1, event.eventVersion());
        assertEquals(NOW, event.occurredAt());
        assertTrue(event.eventId().value().startsWith("evt_"));
    }

    @Test
    void carriesTheOrderInTheEventPayload() {
        Order order = service.create(
            command(MerchantId.generate(), null, "ORDER-7788", 1999, "inr")
        );

        Map<String, Object> payload = outbox.events().get(0).payload();

        assertEquals(order.orderId().value(), payload.get("orderId"));
        assertEquals(order.merchantId().value(), payload.get("merchantId"));
        assertNull(payload.get("customerId"));
        assertEquals(1999L, payload.get("amountMinor"));
        assertEquals("INR", payload.get("currency"));
        assertEquals("ORDER-7788", payload.get("merchantOrderReference"));
        assertEquals("PENDING", payload.get("status"));
        assertEquals(NOW.toString(), payload.get("createdAt"));
    }

    /** A rejected create announces nothing, and never opens a transaction to announce it in. */
    @Test
    void announcesNothingWhenTheOrderIsRefused() {
        MerchantId merchantId = MerchantId.generate();
        service.create(command(merchantId, null, "ORDER-7788", 1999, "INR"));

        assertThrows(
            OrderReferenceAlreadyExistsException.class,
            () -> service.create(command(merchantId, null, "ORDER-7788", 500, "INR"))
        );

        assertEquals(1, outbox.events().size());
        assertEquals(1, transactions.executions());
    }

    // --- merchant order reference ---------------------------------------------

    @Test
    void rejectsAMerchantOrderReferenceAlreadyUsedByTheSameMerchant() {
        MerchantId merchantId = MerchantId.generate();
        service.create(command(merchantId, null, "ORDER-7788", 1999, "INR"));

        assertThrows(
            OrderReferenceAlreadyExistsException.class,
            () -> service.create(command(merchantId, null, "ORDER-7788", 500, "INR"))
        );
    }

    /**
     * merchant_order_ref is the MERCHANT's own key, so two merchants both calling their order
     * "ORDER-7788" is normal, not a conflict.
     */
    @Test
    void allowsTheSameMerchantOrderReferenceUnderADifferentMerchant() {
        service.create(command(MerchantId.generate(), null, "ORDER-7788", 1999, "INR"));

        Order other = service.create(command(MerchantId.generate(), null, "ORDER-7788", 1999, "INR"));

        assertEquals("ORDER-7788", other.merchantOrderReference());
    }

    /**
     * The uniqueness check must run against the normalized reference, not the raw input, or
     * " ORDER-7788 " would slip past a check that the database then rejects.
     */
    @Test
    void checksUniquenessAgainstTheNormalizedMerchantOrderReference() {
        MerchantId merchantId = MerchantId.generate();
        service.create(command(merchantId, null, "ORDER-7788", 1999, "INR"));

        assertThrows(
            OrderReferenceAlreadyExistsException.class,
            () -> service.create(command(merchantId, null, "  ORDER-7788  ", 1999, "INR"))
        );
    }

    @Test
    void allowsManyOrdersWithoutAMerchantOrderReference() {
        MerchantId merchantId = MerchantId.generate();

        service.create(command(merchantId, null, null, 1999, "INR"));
        service.create(command(merchantId, null, "   ", 1999, "INR"));

        assertNull(service.create(command(merchantId, null, null, 1999, "INR")).merchantOrderReference());
    }

    // --- the customer link ----------------------------------------------------

    @Test
    void linksAnOrderToACustomerOfTheSameMerchant() {
        MerchantId merchantId = MerchantId.generate();
        customers.add(merchantId, "cus_11111111-1111-4111-8111-111111111111");

        Order order = service.create(
            command(merchantId, "cus_11111111-1111-4111-8111-111111111111", null, 1999, "INR")
        );

        assertEquals("cus_11111111-1111-4111-8111-111111111111", order.customerId());
    }

    @Test
    void rejectsACustomerThatDoesNotExist() {
        assertThrows(
            CustomerNotFoundForOrderException.class,
            () -> service.create(command(
                MerchantId.generate(), "cus_11111111-1111-4111-8111-111111111111", null, 1999, "INR"
            ))
        );
    }

    /**
     * THE CROSS-TENANT CASE. The customer is real, it just belongs to someone else, and the caller
     * must not be able to tell those two apart -- so the rejection is the same exception with the
     * same message as a customer that never existed.
     */
    @Test
    void rejectsACustomerBelongingToAnotherMerchantWithoutRevealingThatItExists() {
        MerchantId owner = MerchantId.generate();
        MerchantId outsider = MerchantId.generate();
        customers.add(owner, "cus_11111111-1111-4111-8111-111111111111");

        String forStranger = assertThrows(
            CustomerNotFoundForOrderException.class,
            () -> service.create(command(
                outsider, "cus_11111111-1111-4111-8111-111111111111", null, 1999, "INR"
            ))
        ).getMessage();

        String forUnknown = assertThrows(
            CustomerNotFoundForOrderException.class,
            () -> service.create(command(
                outsider, "cus_22222222-2222-4222-8222-222222222222", null, 1999, "INR"
            ))
        ).getMessage();

        assertEquals(
            forUnknown.replace("cus_22222222-2222-4222-8222-222222222222", "X"),
            forStranger.replace("cus_11111111-1111-4111-8111-111111111111", "X")
        );
    }

    @Test
    void doesNotConsultTheCustomerModuleWhenNoCustomerIsNamed() {
        service.create(command(MerchantId.generate(), null, null, 1999, "INR"));

        assertEquals(0, customers.lookups());
    }

    private static CreateOrderCommand command(
        MerchantId merchantId,
        String customerId,
        String merchantOrderReference,
        long amountMinor,
        String currency
    ) {
        return new CreateOrderCommand(
            merchantId,
            customerId,
            merchantOrderReference,
            amountMinor,
            currency,
            null,
            Map.of(),
            null
        );
    }

    /**
     * Runs the callback straight through and counts the calls. It cannot roll anything back -- a
     * plain JUnit test has no database to roll back -- so it proves the boundary was *entered*, not
     * that it holds. Proving it holds needs PostgreSQL, which is OutboxTransactionIntegrationTest's
     * job.
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

    /** Stands in for the outbox, and remembers whether it was called inside a transaction. */
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

    /** Stands in for the customer module across the port. */
    private static final class KnownCustomers implements CustomerLookup {

        private final Set<String> customers = new HashSet<>();
        private int lookups;

        void add(MerchantId merchantId, String customerId) {
            customers.add(merchantId.value() + "/" + customerId);
        }

        int lookups() {
            return lookups;
        }

        @Override
        public boolean exists(MerchantId merchantId, String customerId) {
            lookups++;
            return customers.contains(merchantId.value() + "/" + customerId);
        }
    }
}
