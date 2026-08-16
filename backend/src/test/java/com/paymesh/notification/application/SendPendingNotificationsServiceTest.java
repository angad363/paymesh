package com.paymesh.notification.application;

import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;
import com.paymesh.notification.domain.NotificationStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SendPendingNotificationsServiceTest {

    private static final MerchantId MERCHANT =
        MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000");

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private final FakeNotifications notifications = new FakeNotifications();
    private final ScriptedSender sender = new ScriptedSender();
    private final CountingTransactions transactions = new CountingTransactions();

    private SendPendingNotificationsService service(int maxAttempts) {
        return new SendPendingNotificationsService(
            notifications, sender, transactions, Clock.fixed(NOW, ZoneOffset.UTC), 50, maxAttempts
        );
    }

    private Notification seedPending(String sourceEventId) {
        Notification notification = Notification.record(
            NotificationId.generate(), MERCHANT, sourceEventId, "payment.succeeded",
            "Payment received", "body", NOW
        );

        notifications.saveIfAbsent(notification);

        return notification;
    }

    @Test
    void sendsADuePendingNotification() {
        Notification seeded = seedPending("evt_1");

        SendPendingNotificationsService.DispatchResult result = service(5).dispatch();

        assertThat(result.examined()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);

        Notification stored = notifications.findById(seeded.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(stored.sentAt()).isEqualTo(NOW);
    }

    @Test
    void runsOneTransactionPerNotification() {
        seedPending("evt_1");
        seedPending("evt_2");

        service(5).dispatch();

        assertThat(transactions.executions()).isEqualTo(2);
    }

    @Test
    void reschedulesArefusedNotificationWhileBudgetRemains() {
        Notification seeded = seedPending("evt_1");
        sender.refuseWith("provider down");

        SendPendingNotificationsService.DispatchResult result = service(5).dispatch();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.sent()).isZero();

        Notification stored = notifications.findById(seeded.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(stored.attemptCount()).isEqualTo(1);
        assertThat(stored.lastError()).isEqualTo("provider down");
    }

    @Test
    void failsANotificationOnceTheBudgetIsSpent() {
        Notification seeded = seedPending("evt_1");
        sender.refuseWith("provider down");

        SendPendingNotificationsService.DispatchResult result = service(1).dispatch();

        assertThat(result.failed()).isEqualTo(1);

        Notification stored = notifications.findById(seeded.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(NotificationStatus.FAILED);
    }

    /** One throwing row costs one notification, not the pass -- the open-item-2 guard. */
    @Test
    void oneThrowingNotificationDoesNotStopTheOthers() {
        seedPending("evt_1");
        Notification second = seedPending("evt_2");
        sender.throwOnFirstSend();

        SendPendingNotificationsService.DispatchResult result = service(5).dispatch();

        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        assertThat(notifications.findById(second.id()).orElseThrow().status())
            .isEqualTo(NotificationStatus.SENT);
    }

    // --- fakes ----------------------------------------------------------------------------------

    private static final class CountingTransactions extends TransactionTemplate {

        private int executions;

        int executions() {
            return executions;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;

            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }

    /** In-memory notifications, keyed by id, insertion-ordered like the PENDING index. */
    private static final class FakeNotifications implements NotificationRepository {

        private final Map<String, Notification> byId = new LinkedHashMap<>();

        @Override
        public boolean saveIfAbsent(Notification notification) {
            boolean present = byId.values().stream()
                .anyMatch(n -> n.sourceEventId().equals(notification.sourceEventId()));

            if (present) {
                return false;
            }

            byId.put(notification.id().value(), notification);

            return true;
        }

        @Override
        public Notification save(Notification notification) {
            byId.put(notification.id().value(), notification);

            return notification;
        }

        @Override
        public Optional<Notification> findById(NotificationId id) {
            return Optional.ofNullable(byId.get(id.value()));
        }

        @Override
        public List<String> findDue(int limit) {
            List<String> due = new ArrayList<>();

            for (Notification notification : byId.values()) {
                if (notification.status() == NotificationStatus.PENDING) {
                    due.add(notification.id().value());
                }

                if (due.size() == limit) {
                    break;
                }
            }

            return due;
        }

        @Override
        public Optional<Notification> claim(NotificationId id) {
            return findById(id).filter(n -> n.status() == NotificationStatus.PENDING);
        }
    }

    private static final class ScriptedSender implements NotificationSender {

        private String refusal;
        private boolean throwOnFirst;
        private int sends;

        void refuseWith(String error) {
            this.refusal = error;
        }

        void throwOnFirstSend() {
            this.throwOnFirst = true;
        }

        @Override
        public SendResult send(Notification notification) {
            sends++;

            if (throwOnFirst && sends == 1) {
                throw new RuntimeException("sender blew up");
            }

            return refusal == null ? SendResult.accepted() : SendResult.refused(refusal);
        }
    }
}
