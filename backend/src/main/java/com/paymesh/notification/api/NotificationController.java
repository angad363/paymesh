package com.paymesh.notification.api;

import com.paymesh.notification.application.GetNotificationService;
import com.paymesh.notification.domain.NotificationId;
import com.paymesh.shared.security.AuthenticatedCaller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one read surface on Notification: a support engineer looking up why a merchant was, or was
 * not, told something (ADR-033).
 *
 * <h2>PLATFORM_ADMIN, AND THAT IS WHY IT IS AN /internal ROUTE</h2>
 *
 * {@code requirePlatformAdmin()} means a merchant's token reaches the handler and is refused 403 --
 * support looks across tenants by notification id, which is not a tenant's power. The route is off
 * {@code /api/} for the same reason the callback routes are: the two audiences do not share a
 * surface. It is not in {@code SecurityConfiguration}'s permit list, so the filter chain's
 * default-deny requires a valid token before this handler runs, and the role check does the rest.
 */
@RestController
@RequestMapping("internal/v1/notifications")
public final class NotificationController {

    private final GetNotificationService getNotification;

    public NotificationController(GetNotificationService getNotification) {
        this.getNotification = getNotification;
    }

    @GetMapping("/{notificationId}")
    NotificationResponse get(@PathVariable String notificationId, AuthenticatedCaller caller) {
        caller.requirePlatformAdmin();

        return NotificationResponse.from(getNotification.get(NotificationId.from(notificationId)));
    }
}
