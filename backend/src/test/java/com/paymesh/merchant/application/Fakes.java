package com.paymesh.merchant.application;

import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The collaborators merchant service tests need once ADR-039 gave the merchant an outbox. Same shape
 * and name as Payment's and Identity's {@code Fakes}.
 */
final class Fakes {

    private Fakes() {
    }

    /**
     * Runs the callback straight through and counts the calls. It cannot roll anything back -- a
     * plain JUnit test has no database -- so it proves the boundary was *entered*, not that it holds.
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
}
