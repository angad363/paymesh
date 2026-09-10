package com.paymesh.reporting.api;

import com.paymesh.reporting.application.GetReportExportService;
import com.paymesh.reporting.application.RequestReportExportService;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * {@code /api/v1/report-exports} -- SDD 19.2's asynchronous CSV.
 *
 * <h2>ONE ROUTE FOR STATUS AND FILE, VIA CONTENT NEGOTIATION</h2>
 *
 * {@code GET .../{id}} answers JSON metadata by default and the CSV under {@code Accept: text/csv}.
 * That is the same four endpoints the phase-2 plan named, and it is what {@code Accept} is for: one
 * resource, two representations. A second {@code /content} route would be a second URL for the same
 * thing, and a merchant polling for readiness would have to know both.
 *
 * <p>Asking for the CSV before it exists is a <b>409, not a 404</b>: the export exists, the
 * representation does not yet, and a 404 would tell the merchant to stop polling.
 *
 * <h2>202, not 201</h2>
 *
 * The row is created but the work has not happened. {@code Location} points at the same resource so
 * a client can poll it without assembling a URL.
 */
@RestController
@RequestMapping("api/v1/report-exports")
public final class ReportExportController {

    private final RequestReportExportService requestExport;
    private final GetReportExportService getExport;
    private final Clock clock;

    public ReportExportController(
        RequestReportExportService requestExport,
        GetReportExportService getExport,
        Clock clock
    ) {
        this.requestExport = requestExport;
        this.getExport = getExport;
        this.clock = clock;
    }

    /**
     * @param request optional -- an export with no window means the default thirty days, exactly as
     *     the report endpoints default
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReportExportResponse> create(
        @RequestBody(required = false) CreateReportExportRequest request,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchant();

        String from = request == null || request.from() == null ? null : request.from().toString();
        String to = request == null || request.to() == null ? null : request.to().toString();

        ReportExportResponse response = ReportExportResponse.from(
            requestExport.request(merchantId, ReportWindows.parse(from, to, clock))
        );

        return ResponseEntity
            .accepted()
            .header(HttpHeaders.LOCATION, "/api/v1/report-exports/" + response.id())
            .body(response);
    }

    /**
     * ONE HANDLER FOR BOTH REPRESENTATIONS, branching on {@code Accept} rather than two methods that
     * differ only by {@code produces}.
     *
     * <p>Two mappings with {@code produces = json} and {@code produces = text/csv} both match a
     * request that sends a wildcard {@code Accept} -- which every browser and default {@code curl} does --
     * and Spring's choice between two equally-acceptable concrete types is not something to leave to
     * chance: a poller that lands on the CSV branch of a PENDING export gets a 409 where it expected
     * a status body. So the CSV is served only when it is asked for EXPLICITLY, and everything else,
     * a wildcard Accept included, gets the JSON status a poller relies on.
     *
     * <p>The CSV's {@code Content-Disposition} names the file after the export id rather than the
     * window, so two exports of one window do not overwrite each other in a downloads folder.
     */
    @GetMapping("/{reportExportId}")
    ResponseEntity<?> get(
        @PathVariable String reportExportId,
        @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchant();
        ReportExportId id = ReportExportId.from(reportExportId);

        if (wantsCsv(accept)) {
            String csv = getExport.download(merchantId, id);

            return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + id.value() + ".csv\""
                )
                .body(csv);
        }

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(ReportExportResponse.from(getExport.get(merchantId, id)));
    }

    /** The CSV is served only on an explicit {@code text/csv}; a wildcard, JSON and absent all get JSON. */
    private static boolean wantsCsv(String accept) {
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/csv");
    }
}
