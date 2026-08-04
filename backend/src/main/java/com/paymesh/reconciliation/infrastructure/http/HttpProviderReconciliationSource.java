package com.paymesh.reconciliation.infrastructure.http;

import com.paymesh.reconciliation.application.ProviderDayReport;
import com.paymesh.reconciliation.application.ProviderReconciliationSource;
import com.paymesh.reconciliation.application.ProviderReportUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Fetches the provider's daily report over HTTP, which is the only way PayMesh is allowed to ask.
 *
 * <h2>WHY THIS IS AN HTTP CALL TO A SERVICE IN THE SAME JVM</h2>
 *
 * It looks absurd on the face of it: the simulator's {@code ExportReconciliationService} is one
 * Java call away, and this goes out through the loopback interface to reach it.
 * <p>
 * It is not a mistake, and {@code ModuleBoundaryTest.noCapabilityImportsTheSimulator} enforces it
 * with an empty allowlist. SDD 13.2 says the simulator owns no PayMesh state and its only influence
 * is HTTP; ADR-017 makes the simulator removable from a deployment entirely. A direct call would
 * break both instantly and silently: the "provider" would become a compile-time dependency of the
 * money path, and reconciliation would work against exactly one provider forever -- the one that
 * cannot be a real one.
 * <p>
 * What the loopback hop buys, concretely: this adapter is the ONLY file that changes when the
 * provider becomes an SFTP drop or a signed CSV, and it exercises the same serialization, the same
 * timeouts and the same error paths a real integration would.
 *
 * <h2>Authentication</h2>
 *
 * {@code /sim/v1/**} is guarded by {@code SimulatorApiKeyFilter}, so the key travels on every
 * request. It is configuration, not a secret this class knows how to mint.
 */
public final class HttpProviderReconciliationSource implements ProviderReconciliationSource {

    private final RestClient http;
    private final String apiKeyHeader;
    private final String apiKey;

    public HttpProviderReconciliationSource(RestClient http, String apiKeyHeader, String apiKey) {
        this.http = http;
        this.apiKeyHeader = apiKeyHeader;
        this.apiKey = apiKey;
    }

    /**
     * Every failure becomes {@link ProviderReportUnavailableException}, and none becomes an empty
     * report. A connection refused, a 500 and a body that will not parse all mean the same thing to
     * the job -- nothing was read -- and the one outcome that must never be produced here is a
     * report with no rows, which the job would treat as a clean, quiet day.
     */
    @Override
    public ProviderDayReport fetch(LocalDate date) {
        try {
            ReconciliationDocument document = http.get()
                .uri("/sim/v1/reconciliation/{date}", DateTimeFormatter.ISO_LOCAL_DATE.format(date))
                .header(apiKeyHeader, apiKey)
                .header(HttpHeaders.ACCEPT, "application/json")
                .retrieve()
                .body(ReconciliationDocument.class);

            if (document == null) {
                throw new ProviderReportUnavailableException(date, "the provider returned no body", null);
            }

            return toReport(date, document);
        } catch (RestClientException transportOrParse) {
            throw new ProviderReportUnavailableException(
                date, transportOrParse.getMessage(), transportOrParse
            );
        }
    }

    private static ProviderDayReport toReport(LocalDate date, ReconciliationDocument document) {
        return new ProviderDayReport(
            date,
            nullSafe(document.payments()).stream()
                .map(row -> new ProviderDayReport.Payment(
                    row.callbackReference(),
                    row.providerPaymentId(),
                    row.status(),
                    row.amountMinor(),
                    row.capturedAmountMinor(),
                    row.failureCode(),
                    row.failureMessage(),
                    row.updatedAt()
                ))
                .toList(),
            nullSafe(document.refunds()).stream()
                .map(row -> new ProviderDayReport.Refund(
                    row.callbackReference(),
                    row.providerRefundId(),
                    row.providerPaymentId(),
                    row.status(),
                    row.amountMinor(),
                    row.failureCode(),
                    row.failureMessage(),
                    row.updatedAt()
                ))
                .toList()
        );
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * The wire shape, DECLARED HERE RATHER THAN IMPORTED, for the same reason {@code CallbackBody}
     * restates {@code ProviderCallbackRequest} on the far side of this boundary. Only the fields
     * reconciliation acts on are declared; the rest of the provider's response is ignored by the
     * parser, which is what lets the provider add a field without breaking this.
     */
    private record ReconciliationDocument(List<PaymentRow> payments, List<RefundRow> refunds) {

        private record PaymentRow(
            String providerPaymentId,
            String callbackReference,
            String status,
            long amountMinor,
            long capturedAmountMinor,
            String failureCode,
            String failureMessage,
            Instant updatedAt
        ) {
        }

        private record RefundRow(
            String providerRefundId,
            String providerPaymentId,
            String callbackReference,
            String status,
            long amountMinor,
            String failureCode,
            String failureMessage,
            Instant updatedAt
        ) {
        }
    }
}
