package com.paymesh.audit.api;

import com.paymesh.audit.application.AuditEventQuery;
import com.paymesh.audit.application.GetAuditEventService;
import com.paymesh.audit.application.ListAuditEventsService;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * {@code /internal/v1/audit-events} -- the platform-staff read surface (SDD 19.3, ADR-035).
 *
 * <h2>PLATFORM_ADMIN, AND THAT IS WHY IT IS AN /internal ROUTE</h2>
 *
 * {@code requirePlatformAdmin()} means a merchant's token reaches the handler and is refused 403 --
 * this reads across tenants by merchant, action or actor, which is not a tenant's power. Off
 * {@code /api/} for the same reason the callback and notification-support routes are: the two
 * audiences do not share a surface. Not in {@code SecurityConfiguration}'s permit list, so the
 * filter chain's default-deny requires a valid token before this handler runs and the role check
 * does the rest -- the same shape {@code NotificationController} has.
 *
 * <h2>THE FILTER PARAMETERS ARE NOT A TENANT SCOPE</h2>
 *
 * {@code merchantId} narrows the read; it does not fence the caller. On an {@code /api/} route the
 * tenant is derived from the token and never accepted as a parameter -- here the opposite is
 * correct, because platform staff legitimately read one tenant's history or all of it.
 */
@RestController
@RequestMapping("internal/v1/audit-events")
public final class AuditEventController {

    /** The most rows one call returns. A support read, capped like the webhook deliveries endpoint. */
    static final int MAX_LIMIT = 200;
    static final int DEFAULT_LIMIT = 50;

    private final ListAuditEventsService listEvents;
    private final GetAuditEventService getEvent;
    private final Clock clock;

    public AuditEventController(
        ListAuditEventsService listEvents, GetAuditEventService getEvent, Clock clock
    ) {
        this.listEvents = listEvents;
        this.getEvent = getEvent;
        this.clock = clock;
    }

    @GetMapping
    List<AuditEventResponse> list(
        @RequestParam(required = false) String merchantId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) Integer limit,
        AuthenticatedCaller caller
    ) {
        caller.requirePlatformAdmin();

        AuditEventQuery query = new AuditEventQuery(
            merchantId == null ? null : MerchantId.from(merchantId),
            action,
            actorId,
            AuditWindows.parseOrNull(from, to, clock),
            cappedLimit(limit)
        );

        return listEvents.list(query).stream().map(AuditEventResponse::from).toList();
    }

    @GetMapping("/{auditEventId}")
    AuditEventResponse get(@PathVariable String auditEventId, AuthenticatedCaller caller) {
        caller.requirePlatformAdmin();

        return AuditEventResponse.from(getEvent.get(AuditEventId.from(auditEventId)));
    }

    /** Absent -> the default; anything over the cap is clamped rather than refused. Zero or negative is the caller's 400. */
    private static int cappedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }

        return Math.min(limit, MAX_LIMIT);
    }
}
