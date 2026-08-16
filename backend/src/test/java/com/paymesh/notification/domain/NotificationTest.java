package com.paymesh.notification.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private static final MerchantId MERCHANT =
        MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000");

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private static Notification pending() {
        return Notification.record(
            NotificationId.generate(), MERCHANT, "evt_x", "payment.succeeded",
            "Payment received", "Payment pi_x for 1500 USD succeeded.", NOW
        );
    }

    @Test
    void recordStartsPendingAndUnsent() {
        Notification notification = pending();

        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.attemptCount()).isZero();
        assertThat(notification.sentAt()).isNull();
        assertThat(notification.lastError()).isNull();
    }

    @Test
    void sendMarksSentAndStampsTheTime() {
        Instant later = NOW.plusSeconds(30);

        Notification sent = pending().send(later);

        assertThat(sent.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.sentAt()).isEqualTo(later);
        assertThat(sent.attemptCount()).isEqualTo(1);
    }

    /**
     * The attempt budget is terminal, like ADR-025's dead letter. Two failures with a budget of
     * three stay PENDING to be retried; the third gives up to FAILED.
     */
    @Test
    void attemptFailedRetriesUntilTheBudgetIsSpentThenFails() {
        Notification first = pending().attemptFailed("boom", 3, NOW);
        assertThat(first.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(first.lastError()).isEqualTo("boom");
        assertThat(first.sentAt()).isNull();

        Notification second = first.attemptFailed("boom", 3, NOW);
        assertThat(second.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(second.attemptCount()).isEqualTo(2);

        Notification third = second.attemptFailed("boom", 3, NOW);
        assertThat(third.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(third.attemptCount()).isEqualTo(3);
    }

    @Test
    void recordRejectsBlankRenderedContent() {
        assertThatThrownBy(() -> Notification.record(
            NotificationId.generate(), MERCHANT, "evt_x", "payment.succeeded", " ", "body", NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
