package com.paymesh.notification.application;

import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;
import com.paymesh.notification.domain.NotificationStatus;
import com.paymesh.notification.application.NotificationSender.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * One pass over the PENDING notifications, sending each. The reviewed record-then-send shape from
 * Webhook and the simulator, simplified: no HTTP, no signing, no backoff, because the sender is
 * simulated (ADR-033).
 *
 * <h2>One transaction per notification, claimed with SKIP LOCKED</h2>
 *
 * The candidate list is read unlocked ({@link NotificationRepository#findDue}); each id is then
 * re-read under its own lock. One slow send cannot roll back the notifications beside it, and a
 * second dispatcher takes different rows rather than queueing behind this one.
 *
 * <h2>One bad row must not disable the pass</h2>
 *
 * {@code findDue} returns raw strings and {@code NotificationId.from} -- which validates and throws
 * -- is called INSIDE the per-item try, so a malformed id costs one notification, not the sweep.
 * This is open item 2 in docs/project-status.md, avoided by construction.
 */
public final class SendPendingNotificationsService {

    private static final Logger log = LoggerFactory.getLogger(SendPendingNotificationsService.class);

    private final NotificationRepository notifications;
    private final NotificationSender sender;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public SendPendingNotificationsService(
        NotificationRepository notifications,
        NotificationSender sender,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize,
        int maxAttempts
    ) {
        this.notifications = notifications;
        this.sender = sender;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    public DispatchResult dispatch() {
        List<String> due = notifications.findDue(batchSize);

        int sent = 0;
        int retried = 0;
        int failed = 0;
        int errored = 0;

        for (String candidate : due) {
            Attempt attempt;

            try {
                attempt = sendOne(NotificationId.from(candidate));
            } catch (RuntimeException failure) {
                // ONE BAD ROW IS ONE LOST NOTIFICATION, NOT THE PASS. Still PENDING, picked up next
                // time. Same guard as DeliverWebhooksService.
                log.error("Notification {} threw and will be retried", candidate, failure);

                errored++;

                continue;
            }

            switch (attempt) {
                case SENT -> sent++;
                case RETRIED -> retried++;
                case FAILED -> failed++;
                case GONE -> {
                    // Claimed by a concurrent dispatcher, or no longer PENDING, between the candidate
                    // read and the lock. SKIP LOCKED makes that a no-op.
                }
            }
        }

        return new DispatchResult(due.size(), sent, retried, failed, errored);
    }

    private Attempt sendOne(NotificationId id) {
        return transactions.execute(status -> {
            Instant now = Instant.now(clock);

            Notification notification = notifications.claim(id).orElse(null);

            if (notification == null) {
                return Attempt.GONE;
            }

            SendResult result = sender.send(notification);

            if (result.delivered()) {
                notifications.save(notification.send(now));

                return Attempt.SENT;
            }

            Notification afterFailure = notification.attemptFailed(result.error(), maxAttempts, now);

            notifications.save(afterFailure);

            if (afterFailure.status() == NotificationStatus.FAILED) {
                log.warn(
                    "Notification {} gave up after {} attempts, last error: {}",
                    id.value(), afterFailure.attemptCount(), result.error()
                );

                return Attempt.FAILED;
            }

            return Attempt.RETRIED;
        });
    }

    private enum Attempt {
        SENT,
        RETRIED,
        FAILED,
        GONE
    }

    /**
     * What one pass did, counted so the scheduled bean can log something worth reading.
     *
     * @param errored threw and was logged; the row is still PENDING and the next pass retries it
     */
    public record DispatchResult(int examined, int sent, int retried, int failed, int errored) {
    }
}
