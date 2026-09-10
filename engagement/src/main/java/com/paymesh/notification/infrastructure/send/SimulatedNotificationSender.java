package com.paymesh.notification.infrastructure.send;

import com.paymesh.notification.application.NotificationSender;
import com.paymesh.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only sender today. PayMesh moves no real money and sends no real mail (ADR-033), so this
 * logs the notification and reports success. It is where a real email/SMS provider -- or a failure
 * profile like the simulator's -- would plug in; the dispatcher does not change when it does.
 *
 * <p>Because it always accepts, {@code FAILED} and {@code attempt_count > 0} are unreachable in
 * production until a sender that can fail replaces this one. The dispatcher's retry/fail path is
 * exercised in tests with a sender that throws, the same way the simulator's failure profiles reach
 * states the happy path never does.
 */
public final class SimulatedNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedNotificationSender.class);

    @Override
    public SendResult send(Notification notification) {
        log.info(
            "Simulated notification sent id={} merchant={} type={} subject=\"{}\"",
            notification.id().value(),
            notification.merchantId().value(),
            notification.eventType(),
            notification.subject()
        );

        return SendResult.accepted();
    }
}
