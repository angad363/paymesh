package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The collaborators every payment service test needs, in one place.
 * <p>
 * They started as private classes inside {@code CreatePaymentIntentServiceTest} and moved here when
 * attach and confirm needed the same four. Three copies of a transaction-counting
 * {@code TransactionTemplate} would have been three chances for one of them to stop counting.
 * (Identity's {@code Fakes} set the precedent for the name.)
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

    static final class RecordingHistory implements PaymentStateHistoryRepository {

        private final ImmediateTransactions transactions;
        private final List<PaymentStateChange> changes = new ArrayList<>();
        private boolean appendedInsideATransaction;

        RecordingHistory(ImmediateTransactions transactions) {
            this.transactions = transactions;
        }

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
    static final class KnownOrders implements OrderLookup {

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

        /**
         * A map cannot hold a row still, so this double preserves the SIGNATURE and the answer, not
         * the lock. That the create and confirm paths lock at all is proved against PostgreSQL,
         * which is the only thing that can arbitrate it.
         */
        @Override
        public Optional<PayableOrder> findForUpdate(MerchantId merchantId, String orderId) {
            return find(merchantId, orderId);
        }
    }

    /**
     * Attempts in a list. Like the intent repository double it enforces tenant scoping and counts
     * the same way the real query does, but it cannot arbitrate a race -- two concurrent confirms
     * losing to {@code uq_payment_attempts_intent_number} is proved against PostgreSQL.
     */
    static final class RecordingAttempts implements PaymentAttemptRepository {

        private final ImmediateTransactions transactions;
        private final List<PaymentAttempt> attempts = new ArrayList<>();
        private boolean appendedInsideATransaction;

        RecordingAttempts(ImmediateTransactions transactions) {
            this.transactions = transactions;
        }

        List<PaymentAttempt> attempts() {
            return attempts;
        }

        boolean appendedInsideATransaction() {
            return appendedInsideATransaction;
        }

        @Override
        public int nextAttemptNumber(MerchantId merchantId, PaymentIntentId paymentIntentId) {
            return (int) attempts.stream()
                .filter(attempt -> attempt.merchantId().equals(merchantId)
                    && attempt.paymentIntentId().equals(paymentIntentId))
                .count() + 1;
        }

        @Override
        public void append(PaymentAttempt attempt) {
            appendedInsideATransaction = transactions.inside();
            attempts.add(attempt);
        }
    }
}
