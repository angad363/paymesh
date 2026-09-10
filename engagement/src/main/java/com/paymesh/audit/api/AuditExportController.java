package com.paymesh.audit.api;

import com.paymesh.audit.application.GetAuditExportService;
import com.paymesh.audit.application.RequestAuditExportService;
import com.paymesh.audit.domain.AuditExportId;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;

/**
 * {@code /internal/v1/audit-exports} -- SDD 19.3's asynchronous CSV, platform-staff only.
 *
 * <p>Structurally identical to {@code ReportExportController}: {@code POST} records a row and returns
 * 202; {@code GET .../{id}} answers JSON metadata by default and the CSV under {@code Accept:
 * text/csv}; asking for the CSV before it is rendered is a 409, not a 404. The difference is the
 * audience -- {@code requirePlatformAdmin()} rather than {@code requireSingleMerchant()} -- because
 * an audit export reads across tenants and records who ran it.
 */
@RestController
@RequestMapping("internal/v1/audit-exports")
public final class AuditExportController {

    private final RequestAuditExportService requestExport;
    private final GetAuditExportService getExport;
    private final Clock clock;

    public AuditExportController(
        RequestAuditExportService requestExport, GetAuditExportService getExport, Clock clock
    ) {
        this.requestExport = requestExport;
        this.getExport = getExport;
        this.clock = clock;
    }

    /**
     * @param request optional -- an export with no window means the default thirty days, and no
     *     {@code merchantId} means every tenant in the window
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuditExportResponse> create(
        @RequestBody(required = false) CreateAuditExportRequest request,
        AuthenticatedCaller caller
    ) {
        String requestedBy = caller.requirePlatformAdmin();

        String from = request == null || request.from() == null ? null : request.from().toString();
        String to = request == null || request.to() == null ? null : request.to().toString();
        MerchantId merchantFilter = request == null || request.merchantId() == null
            ? null
            : MerchantId.from(request.merchantId());

        AuditExportResponse response = AuditExportResponse.from(
            requestExport.request(requestedBy, merchantFilter, AuditWindows.parse(from, to, clock))
        );

        return ResponseEntity
            .accepted()
            .header(HttpHeaders.LOCATION, "/internal/v1/audit-exports/" + response.id())
            .body(response);
    }

    /**
     * ONE HANDLER FOR BOTH REPRESENTATIONS, branching on {@code Accept} -- the same reasoning
     * {@code ReportExportController} documents: two {@code produces} mappings both match a wildcard
     * {@code Accept}, so the CSV is served only when asked for EXPLICITLY and everything else, a
     * wildcard included, gets the JSON a poller relies on.
     */
    @GetMapping("/{auditExportId}")
    ResponseEntity<?> get(
        @PathVariable String auditExportId,
        @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
        AuthenticatedCaller caller
    ) {
        caller.requirePlatformAdmin();

        AuditExportId id = AuditExportId.from(auditExportId);

        if (wantsCsv(accept)) {
            String csv = getExport.download(id);

            return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + id.value() + ".csv\""
                )
                .body(csv);
        }

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(AuditExportResponse.from(getExport.get(id)));
    }

    /** The CSV is served only on an explicit {@code text/csv}; a wildcard, JSON and absent all get JSON. */
    private static boolean wantsCsv(String accept) {
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/csv");
    }
}
