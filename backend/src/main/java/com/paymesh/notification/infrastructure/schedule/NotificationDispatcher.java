package com.paymesh.notification.infrastructure.schedule;

import com.paymesh.notification.application.SendPendingNotificationsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else -- the reviewed shape ({@code WebhookDispatcher},
 * {@code SimulatorCallbackDispatcher}). No logic here: everything lives in
 * {@link SendPendingNotificationsService}, a plain object taking an injected {@code Clock} that tests
 * drive directly. Absent under {@code dev}, like every other timer in this codebase.
 */
public final class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final SendPendingNotificationsService sendPending;

    public NotificationDispatcher(SendPendingNotificationsService sendPending) {
        this.sendPending = sendPending;
    }

    @Scheduled(
        fixedDelayString = "${paymesh.notification.dispatch.interval}",
        initialDelayString = "${paymesh.notification.dispatch.interval}"
    )
    public void dispatch() {
        SendPendingNotificationsService.DispatchResult result = sendPending.dispatch();

        if (result.examined() > 0) {
            log.info(
                "Notification dispatch examined={} sent={} retried={} failed={} errored={}",
                result.examined(), result.sent(), result.retried(), result.failed(), result.errored()
            );
        }
    }
}
