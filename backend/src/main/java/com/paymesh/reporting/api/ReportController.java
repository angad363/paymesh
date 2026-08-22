package com.paymesh.reporting.api;

import com.paymesh.reporting.application.GetReportsService;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * {@code /api/v1/reports} -- SDD 19.2's two read reports.
 *
 * <h2>READ ONLY, AND EVENTUALLY CONSISTENT, AND IT SAYS SO</h2>
 *
 * Both responses carry an {@code asOf}: the newest fact the projection holds for this merchant, or
 * null when it holds none. Nothing here reports "now", because events committed a moment ago may
 * still be unpublished in the outbox (ADR-016) and a report that hides that is worse than one that
 * admits it.
 *
 * <h2>The tenant is derived, never accepted</h2>
 *
 * From the verified token, like every other merchant-facing read. There is no {@code merchantId}
 * parameter and there must not be one -- a reporting endpoint is exactly the shape where a
 * cross-tenant read returns a plausible number rather than an error.
 *
 * <h2>Why the Clock is here</h2>
 *
 * Only to default {@code to} when the caller omits it. The reports themselves take no clock; their
 * one timestamp comes from the data.
 */
@RestController
@RequestMapping("api/v1/reports")
public final class ReportController {

    private final GetReportsService reports;
    private final Clock clock;

    public ReportController(GetReportsService reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @GetMapping(path = "/payment-summary", produces = MediaType.APPLICATION_JSON_VALUE)
    PaymentSummaryResponse paymentSummary(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchant();

        return PaymentSummaryResponse.from(
            reports.paymentSummary(merchantId, ReportWindows.parse(from, to, clock))
        );
    }

    @GetMapping(path = "/settlements", produces = MediaType.APPLICATION_JSON_VALUE)
    SettlementSummaryResponse settlements(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchant();

        return SettlementSummaryResponse.from(
            reports.settlementSummary(merchantId, ReportWindows.parse(from, to, clock))
        );
    }
}
