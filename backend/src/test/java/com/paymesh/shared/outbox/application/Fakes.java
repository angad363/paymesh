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
     * Runs the callback straight through, counts the calls, and UNDOES WHAT WAS REGISTERED IF IT
     * THROWS.
     * <p>
     * The undo is not decoration. Without it this fake asserted something false: that a failed
     * handler KEEPS its inbox row. A relay test spanning more than one pass then sees the second
     * pass skip the handler as already-processed, so the delivery silently succeeds and a retry
     * budget can never be spent -- the failure looks like it healed. Participants register their own
     * compensation through {@link #onRollback}, which is as much of a transaction as a fake needs to
     * be to stop lying about this one property.
     * <p>
     * It is still not a database, and the limit is worth stating rather than assuming: this proves
     * the boundary was ENTERED and that registered state is undone, not that PostgreSQL would undo
     * an unregistered write. {@code EventDeliveryIntegrationTest} is where that lives.
     */
    static final class ImmediateTransactions extends TransactionTemplate {

        private int executions;
        private boolean inside;
        private List<Runnable> undos = new ArrayList<>();

        int executions() {
            return executions;
        }

        boolean inside() {
            return inside;
        }

        /** Registers a compensation to run if the CURRENT callback throws. Outside one, a no-op. */
        void onRollback(Runnable undo) {
            if (inside) {
                undos.add(undo);
            }
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;
            inside = true;
            List<Runnable> outer = undos;
            undos = new ArrayList<>();

            try {
                T result = action.doInTransaction(new SimpleTransactionStatus());
                return result;
            } catch (RuntimeException rollback) {
                undos.forEach(Runnable::run);
                throw rollback;
            } finally {
                undos = outer;
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

            String key = consumerName + "/" + eventId.value();
            boolean claimedNow = claimed.add(key);

            // The claim goes back if the handler throws -- which is the whole reason the inbox row is
            // written inside the handler's transaction rather than beside it. Without this, a failed
            // delivery would look processed on the next pass and never be retried at all.
            if (claimedNow) {
                transactions.onRollback(() -> claimed.remove(key));
            }

            return claimedNow;
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
        private final Map<String, Integer> attempts = new HashMap<>();
        private final Map<String, String> lastErrors = new HashMap<>();
        private final Map<String, Instant> deadLettered = new HashMap<>();

        /**
         * Set when recording an attempt should blow up, so a test can prove the relay survives its
         * own bookkeeping failing -- which is the one place a throw would otherwise abort the pass.
         */
        private RuntimeException failRecording;

        void append(UnpublishedEvent row) {
            rows.add(row);
        }

        void failAttemptRecordingWith(RuntimeException failure) {
            this.failRecording = failure;
        }

        int attemptsFor(String eventId) {
            return attempts.getOrDefault(eventId, 0);
        }

        String lastErrorFor(String eventId) {
            return lastErrors.get(eventId);
        }

        boolean isDeadLettered(String eventId) {
            return deadLettered.containsKey(eventId);
        }

        Map<String, Instant> published() {
            return published;
        }

        boolean isPublished(String eventId) {
            return published.containsKey(eventId);
        }

        /**
         * Mirrors the real claim query on both exclusions, and the dead-letter one matters: a double
         * that kept returning abandoned rows would let a relay that never actually unblocks an
         * aggregate pass its own test.
         * <p>
         * The {@code attemptCount} carried on each row is refreshed from what has been recorded, so
         * a test can call {@code publish()} repeatedly and watch the budget actually deplete rather
         * than replaying attempt 1 forever.
         */
        @Override
        public List<UnpublishedEvent> findUnpublished(int limit) {
            return rows.stream()
                .filter(row -> !published.containsKey(row.eventId()))
                .filter(row -> !deadLettered.containsKey(row.eventId()))
                .sorted(Comparator.comparing(UnpublishedEvent::occurredAt))
                .limit(limit)
                .map(row -> new UnpublishedEvent(
                    row.eventId(), row.merchantId(), row.aggregateType(), row.aggregateId(),
                    row.eventType(), row.eventVersion(), row.payloadJson(), row.occurredAt(),
                    attempts.getOrDefault(row.eventId(), 0)
                ))
                .toList();
        }

        @Override
        public void markPublished(EventId eventId, Instant publishedAt) {
            published.putIfAbsent(eventId.value(), publishedAt);
        }

        /** The same {@code count + 1 >= maxAttempts} rule the native UPDATE applies. */
        @Override
        public void recordFailedAttempt(
            String eventId, Instant attemptedAt, String error, int maxAttempts
        ) {
            if (failRecording != null) {
                throw failRecording;
            }
            if (published.containsKey(eventId)) {
                // The real statement carries `and published_at is null`.
                return;
            }

            int count = attempts.merge(eventId, 1, Integer::sum);
            lastErrors.put(eventId, error);

            if (count >= maxAttempts) {
                deadLettered.putIfAbsent(eventId, attemptedAt);
            }
        }

        @Override
        public BacklogHealth backlogHealth() {
            Instant oldest = rows.stream()
                .filter(row -> !published.containsKey(row.eventId()))
                .filter(row -> !deadLettered.containsKey(row.eventId()))
                .map(UnpublishedEvent::occurredAt)
                .min(Comparator.naturalOrder())
                .orElse(null);

            return new BacklogHealth(oldest, deadLettered.size());
        }
    }
}
