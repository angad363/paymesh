package com.paymesh.reporting;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.reporting.application.GenerateReportExportsService;
import com.paymesh.reporting.application.GetReportExportService;
import com.paymesh.reporting.application.GetReportsService;
import com.paymesh.reporting.application.PaymentSummary;
import com.paymesh.reporting.application.Report;
import com.paymesh.reporting.application.ReportExportNotReadyException;
import com.paymesh.reporting.application.RequestReportExportService;
import com.paymesh.reporting.application.SettlementSummary;
import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportStatus;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REPORTING AGAINST A REAL POSTGRESQL, driven through the real outbox relay and event dispatcher.
 *
 * <p>The unit tests prove the assembly, the CSV and the export state machine; this proves the wiring
 * and the constraints -- that Reporting is genuinely subscribed, that the native day-bucket query
 * runs against Postgres rather than H2's dialect, that the {@code evt_} primary key makes a
 * redelivery a no-op, and that a fact's row satisfies V34 (the format checks, the currency check).
 *
 * <p>{@code payment.failed} is the driving event, the same choice {@code NotificationIntegrationTest}
 * makes and for the same reason: it is a type Reporting subscribes to whose OTHER consumers (Order,
 * the Ledger) are not fed here, so a synthetic payload naming rows that were never created does not
 * abort the relay pass before Reporting is reached. Reporting and Notification both consume it and
 * both only write.
 *
 * <p>Deliberately not {@code @Transactional}: an outer test transaction would mask whether the
 * dispatcher opened its own, and each test registers its own merchant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ReportingIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-16T10:00:00Z");
    private static final ReportWindow WINDOW = new ReportWindow(
        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")
    );

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private GetReportsService reports;

    @Autowired
    private RequestReportExportService requestExport;

    @Autowired
    private GenerateReportExportsService generateExports;

    @Autowired
    private GetReportExportService getExport;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private JdbcClient jdbc;

    /**
     * THE HEADLINE: a payment fails, the relay dispatches it, and a fact is waiting -- and nothing
     * here calls Reporting. The producer wrote an event; Reporting subscribed.
     */
    @Test
    void projectsAFactWhenAnEventIsRelayed() {
        MerchantId merchantId = merchant();

        String eventId = relayPaymentFailed(merchantId, 1500);

        Map<String, Object> row = factFor(eventId);

        assertThat(row.get("event_type")).isEqualTo("payment.failed");
        assertThat(row.get("merchant_id")).isEqualTo(merchantId.value());
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(((Number) row.get("amount_minor")).longValue()).isEqualTo(1500);
        assertThat(row.get("recorded_at")).isNotNull();
    }

    /** The payment summary reads the projected facts back, per currency, and admits its freshness. */
    @Test
    void aPaymentSummaryCountsTheProjectedFacts() {
        MerchantId merchantId = merchant();
        relayPaymentFailed(merchantId, 1500);
        relayPaymentFailed(merchantId, 2500);

        Report<PaymentSummary> report = reports.paymentSummary(merchantId, WINDOW);

        assertThat(report.asOf()).isNotNull();
        PaymentSummary summary = report.currencies().getFirst();
        assertThat(summary.currency()).isEqualTo("INR");
        assertThat(summary.failedCount()).isEqualTo(2);
        assertThat(summary.failedAmountMinor()).isEqualTo(4000);
    }

    /** A merchant with no facts gets an empty report and a null asOf, not a fabricated timestamp. */
    @Test
    void anUntouchedMerchantGetsAnHonestlyEmptyReport() {
        Report<PaymentSummary> report = reports.paymentSummary(merchant(), WINDOW);

        assertThat(report.currencies()).isEmpty();
        assertThat(report.asOf()).isNull();
    }

    /**
     * A REDELIVERED OUTBOX EVENT IS A NO-OP, and here {@code pk_report_facts} is the real guard: the
     * handler is driven past the inbox, the way a Kafka consumer would see it.
     */
    @Test
    void aSecondDeliveryOfOneEventProjectsNothingNew() {
        MerchantId merchantId = merchant();
        OutboxEvent event = paymentFailed(merchantId, 1500);

        outbox.append(event);
        relay.publish();

        jdbc.sql("delete from processed_events where event_id = ?")
            .param(event.eventId().value())
            .update();
        jdbc.sql("update outbox_events set published_at = null where event_id = ?")
            .param(event.eventId().value())
            .update();

        relay.publish();

        assertThat(countFor(event.eventId().value())).isEqualTo(1);
    }

    /** The full export loop: request, generate, download the CSV. */
    @Test
    void generatesAnExportAndServesItsCsv() {
        MerchantId merchantId = merchant();
        relayPaymentFailed(merchantId, 1500);

        ReportExport requested = requestExport.request(merchantId, WINDOW);
        assertThat(requested.status()).isEqualTo(ReportExportStatus.PENDING);

        // Before generation, the CSV does not exist -- a 409's worth of "not ready", not a 404.
        assertThatThrownBy(() -> getExport.download(merchantId, requested.id()))
            .isInstanceOf(ReportExportNotReadyException.class);

        generateExports.generate();

        ReportExport completed = getExport.get(merchantId, requested.id());
        assertThat(completed.status()).isEqualTo(ReportExportStatus.COMPLETED);
        assertThat(completed.rowCount()).isEqualTo(1);

        String csv = getExport.download(merchantId, requested.id());
        assertThat(csv.lines()).hasSize(2);
        assertThat(csv).contains("payment.failed").contains("INR");
    }

    /** A settlement event lands in the same table and drives the settlement summary. */
    @Test
    void projectsSettlementFactsToo() {
        MerchantId merchantId = merchant();
        relaySettlementBatchCut(merchantId, 90_000);

        SettlementSummary summary =
            reports.settlementSummary(merchantId, WINDOW).currencies().getFirst();

        assertThat(summary.batchesCut()).isEqualTo(1);
        assertThat(summary.cutAmountMinor()).isEqualTo(90_000);
        assertThat(summary.batchesPaid()).isZero();
    }

    /** One merchant's facts never appear in another's report. */
    @Test
    void keepsOneMerchantsFactsOutOfAnothersReport() {
        MerchantId mine = merchant();
        MerchantId theirs = merchant();
        relayPaymentFailed(mine, 1500);

        assertThat(reports.paymentSummary(theirs, WINDOW).currencies()).isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------

    private MerchantId merchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Reporting Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            OCCURRED_AT
        ).activate(OCCURRED_AT)).merchantId();
    }

    private String relayPaymentFailed(MerchantId merchantId, long amountMinor) {
        OutboxEvent event = paymentFailed(merchantId, amountMinor);

        outbox.append(event);
        relay.publish();

        return event.eventId().value();
    }

    private void relaySettlementBatchCut(MerchantId merchantId, long amountMinor) {
        String batchId = "stl_" + UUID.randomUUID();

        Map<String, Object> payload = new HashMap<>();
        payload.put("settlementBatchId", batchId);
        payload.put("merchantId", merchantId.value());
        payload.put("amountMinor", amountMinor);
        payload.put("currency", "INR");
        payload.put("itemCount", 1);
        payload.put("occurredAt", OCCURRED_AT.toString());

        outbox.append(new OutboxEvent(
            EventId.generate(), merchantId, "SETTLEMENT_BATCH", batchId,
            "settlement.batch_cut", 1, payload, OCCURRED_AT
        ));
        relay.publish();
    }

    private OutboxEvent paymentFailed(MerchantId merchantId, long amountMinor) {
        String paymentIntentId = "pi_" + UUID.randomUUID();

        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", paymentIntentId);
        payload.put("orderId", "ord_" + UUID.randomUUID());
        payload.put("amountMinor", amountMinor);
        payload.put("capturedAmountMinor", 0);
        payload.put("currency", "INR");
        payload.put("status", "FAILED");
        payload.put("failureCode", "card_declined");
        payload.put("failureMessage", "Insufficient funds");
        payload.put("failedAt", OCCURRED_AT.toString());

        return new OutboxEvent(
            EventId.generate(), merchantId, "PAYMENT_INTENT", paymentIntentId,
            "payment.failed", 1, payload, OCCURRED_AT
        );
    }

    private Map<String, Object> factFor(String sourceEventId) {
        return jdbc.sql("""
            select source_event_id, merchant_id, event_type, currency, amount_minor, recorded_at
              from report_facts where source_event_id = ?
            """)
            .param(sourceEventId)
            .query()
            .singleRow();
    }

    private int countFor(String sourceEventId) {
        return jdbc.sql("select count(*) from report_facts where source_event_id = ?")
            .param(sourceEventId)
            .query(Integer.class)
            .single();
    }
}
