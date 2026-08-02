package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The collaborators the relay and dispatcher tests need, in one place. Order's, Payment's and
 * Identity's {@code Fakes} set the precedent for the name and the shape.
 */
final class Fakes {

    private Fakes() {
    }

    /**
     * Runs the callback straight through and counts the calls, ROLLING BACK NOTHING.
     * <p>
     * A plain JUnit test has no database, so this proves the boundary was ENTERED, not that it
     * holds. Proving it holds -- that a failed handler takes its inbox row with it -- needs
     * PostgreSQL, and {@code EventDeliveryIntegrationTest} is where that lives. Stating the limit
     * here rather than letting a reader assume otherwise.
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

    /**
     * The inbox as a set, with the same claim semantics the primary key gives the real one: the
     * first caller for a pair gets true, every later caller gets false.
     */
    static final class InMemoryProcessedEvents implements ProcessedEventRepository {

        private final Set<String> claimed = new HashSet<>();
        private final ImmediateTransactions transactions;

        private boolean claimedInsideATransaction;

        InMemoryProcessedEvents(ImmediateTransactions transactions) {
            this.transactions = transactions;
        }

        boolean claimedInsideATransaction() {
            return claimedInsideATransaction;
        }

        int size() {
            return claimed.size();
        }

        @Override
        public boolean markProcessed(
            String consumerName,
            EventId eventId,
            String eventType,
            Instant processedAt
        ) {
            claimedInsideATransaction = transactions.inside();
            return claimed.add(consumerName + "/" + eventId.value());
        }
    }

    /** An inbox that claims nothing, standing in for an event every consumer has already seen. */
    static final class AlreadyProcessedEverything implements ProcessedEventRepository {

        @Override
        public boolean markProcessed(
            String consumerName,
            EventId eventId,
            String eventType,
            Instant processedAt
        ) {
            return false;
        }
    }

    /** Records what it was handed, and can be told to throw for one event. */
    static final class RecordingHandler implements EventHandler {

        private final String consumerName;
        private final String eventType;
        private final Consumer<OutboxEvent> beforeRecording;
        private final List<OutboxEvent> handled = new ArrayList<>();

        RecordingHandler(String consumerName, String eventType) {
            this(consumerName, eventType, event -> {
            });
        }

        RecordingHandler(String consumerName, String eventType, Consumer<OutboxEvent> beforeRecording) {
            this.consumerName = consumerName;
            this.eventType = eventType;
            this.beforeRecording = beforeRecording;
        }

        List<OutboxEvent> handled() {
            return handled;
        }

        List<String> handledAggregateIds() {
            return handled.stream().map(OutboxEvent::aggregateId).toList();
        }

        @Override
        public String consumerName() {
            return consumerName;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(OutboxEvent event) {
            beforeRecording.accept(event);
            handled.add(event);
        }
    }

    /**
     * The outbox as a list of raw rows, preserving the two things the real query promises: only
     * unpublished rows come back, and they come back {@code occurred_at} ascending. A double that
     * returned them in insertion order would hide an ordering bug rather than catch one.
     */
    static final class InMemoryOutbox implements OutboxReader {

        private final List<UnpublishedEvent> rows = new ArrayList<>();
        private final Map<String, Instant> published = new HashMap<>();

        void append(UnpublishedEvent row) {
            rows.add(row);
        }

        Map<String, Instant> published() {
            return published;
        }

        boolean isPublished(String eventId) {
            return published.containsKey(eventId);
        }

        @Override
        public List<UnpublishedEvent> findUnpublished(int limit) {
            return rows.stream()
                .filter(row -> !published.containsKey(row.eventId()))
                .sorted(Comparator.comparing(UnpublishedEvent::occurredAt))
                .limit(limit)
                .toList();
        }

        @Override
        public void markPublished(EventId eventId, Instant publishedAt) {
            published.putIfAbsent(eventId.value(), publishedAt);
        }
    }
}
