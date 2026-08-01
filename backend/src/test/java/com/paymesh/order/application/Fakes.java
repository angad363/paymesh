package com.paymesh.order.application;

import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The collaborators every order service test needs, in one place.
 * <p>
 * They started as private classes inside {@code CreateOrderServiceTest} and moved here when cancel
 * and the expiry sweep needed the same ones. Three copies of a transaction-counting
 * {@code TransactionTemplate} would have been three chances for one of them to stop counting.
 * (Payment's and Identity's {@code Fakes} set the precedent for the name and the shape.)
 */
final class Fakes {

    private Fakes() {
    }

    /**
     * Runs the callback straight through and counts the calls. It cannot roll anything back -- a
     * plain JUnit test has no database to roll back -- so it proves the boundary was *entered*, not
     * that it holds. Proving it holds needs PostgreSQL.
     */
    static final class ImmediateTransactions extends TransactionTemplate {

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
    static final class RecordingOutbox implements OutboxWriter {

        private final ImmediateTransactions transactions;
        private final List<OutboxEvent> events = new ArrayList<>();
        private boolean appendedInsideATransaction;

        RecordingOutbox(ImmediateTransactions transactions) {
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

    /** Stands in for order_state_history, and remembers the same thing about the transaction. */
    static final class RecordingHistory implements OrderStateHistoryRepository {

        private final ImmediateTransactions transactions;
        private final List<OrderStateChange> changes = new ArrayList<>();
        private boolean appendedInsideATransaction;

        RecordingHistory(ImmediateTransactions transactions) {
            this.transactions = transactions;
        }

        List<OrderStateChange> changes() {
            return changes;
        }

        boolean appendedInsideATransaction() {
            return appendedInsideATransaction;
        }

        @Override
        public void append(OrderStateChange change) {
            appendedInsideATransaction = transactions.inside();
            changes.add(change);
        }
    }

    /** Stands in for the customer module across the port. */
    static final class KnownCustomers implements CustomerLookup {

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

    /**
     * Stands in for the payment module across the port Order declares and Payment implements.
     * <p>
     * A set, so a test can say "this order is being collected against" without a payment intent, a
     * database or the Payment module in the picture at all. That is the whole point of the port: the
     * sweeper's rule is testable in plain JUnit. Whether the REAL answer matches Payment's slot
     * definition is proved separately, against PostgreSQL, with an actual intent.
     */
    static final class StubPaymentActivity implements PaymentActivityLookup {

        private final Set<String> live = new HashSet<>();
        private int lookups;

        void addLiveIntentFor(MerchantId merchantId, String orderId) {
            live.add(merchantId.value() + "/" + orderId);
        }

        int lookups() {
            return lookups;
        }

        @Override
        public boolean hasLivePaymentIntent(MerchantId merchantId, String orderId) {
            lookups++;
            return live.contains(merchantId.value() + "/" + orderId);
        }
    }
}
