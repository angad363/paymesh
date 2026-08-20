package com.paymesh.reporting.infrastructure.events;

import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.infrastructure.events.ReportFactExtractor.Extracted;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payload reading, which is where a wrong field name would be silently wrong: an extraction
 * taking {@code amountMinor} from a succeeded payment still produces a number, and the report would
 * overstate every partial capture without failing anything.
 */
class ReportFactExtractorTest {

    /**
     * THE ONE THAT WOULD BE SILENTLY WRONG. A partial capture collects less than the intent was for,
     * and a summary of what a merchant COLLECTED must report the smaller number.
     */
    @Test
    void aSucceededPaymentReportsWhatWasCapturedRatherThanWhatWasAttempted() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", "pi_1");
        payload.put("orderId", "ord_1");
        payload.put("amountMinor", 99_900);
        payload.put("capturedAmountMinor", 30_000);
        payload.put("currency", "USD");

        Extracted extracted = ReportFactExtractor.extract("payment.succeeded", payload);

        assertThat(extracted.amountMinor()).isEqualTo(30_000);
        assertThat(extracted.subjectId()).isEqualTo("pi_1");
        assertThat(extracted.orderId()).isEqualTo("ord_1");
        assertThat(extracted.currency()).isEqualTo("USD");
    }

    /** Nothing was captured, so the failure reports what was attempted. */
    @Test
    void aFailedPaymentReportsWhatWasAttempted() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", "pi_2");
        payload.put("orderId", "ord_2");
        payload.put("amountMinor", 99_900);
        payload.put("currency", "USD");

        assertThat(ReportFactExtractor.extract("payment.failed", payload).amountMinor())
            .isEqualTo(99_900);
    }

    /** A guest checkout has no order. The fact carries a null rather than refusing the event. */
    @Test
    void toleratesAPaymentWithNoOrder() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", "pi_3");
        payload.put("orderId", null);
        payload.put("amountMinor", 100);
        payload.put("capturedAmountMinor", 100);
        payload.put("currency", "USD");

        assertThat(ReportFactExtractor.extract("payment.succeeded", payload).orderId()).isNull();
    }

    /**
     * A refund's payload names the PAYMENT it reverses, never an order. Joining through Payment to
     * find one would be a read of another capability's table, which this capability must never do.
     */
    @Test
    void aRefundIsAboutItselfAndCarriesNoOrder() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("refundId", "ref_1");
        payload.put("paymentIntentId", "pi_1");
        payload.put("amountMinor", 30_000);
        payload.put("currency", "USD");

        Extracted extracted = ReportFactExtractor.extract("refund.succeeded", payload);

        assertThat(extracted.subjectId()).isEqualTo("ref_1");
        assertThat(extracted.orderId()).isNull();
        assertThat(extracted.amountMinor()).isEqualTo(30_000);
    }

    /** All three settlement events carry the batch, so all three read the same three keys. */
    @Test
    void everySettlementEventIsAboutTheBatch() {
        for (String eventType : ReportFact.SETTLEMENT_TYPES) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("settlementBatchId", "stl_1");
            payload.put("amountMinor", 90_000);
            payload.put("currency", "USD");

            Extracted extracted = ReportFactExtractor.extract(eventType, payload);

            assertThat(extracted.subjectId()).as(eventType).isEqualTo("stl_1");
            assertThat(extracted.amountMinor()).as(eventType).isEqualTo(90_000);
            assertThat(extracted.orderId()).as(eventType).isNull();
        }
    }

    /**
     * JSONB hands back an {@code Integer} for anything that fits in 32 bits and a {@code Long}
     * otherwise, so the amount is read through {@link Number}. A cast to one of them would work for
     * every test fixture and throw on the first genuinely large payment.
     */
    @Test
    void readsAnAmountWhicheverNumericTypeJsonbReturns() {
        Map<String, Object> asInteger = new HashMap<>();
        asInteger.put("settlementBatchId", "stl_1");
        asInteger.put("currency", "USD");
        asInteger.put("amountMinor", 90_000);

        Map<String, Object> asLong = new HashMap<>(asInteger);
        asLong.put("amountMinor", 9_000_000_000L);

        assertThat(ReportFactExtractor.extract("payout.paid", asInteger).amountMinor())
            .isEqualTo(90_000L);
        assertThat(ReportFactExtractor.extract("payout.paid", asLong).amountMinor())
            .isEqualTo(9_000_000_000L);
    }

    /** Throwing is what leaves the event unpublished so the relay retries and eventually gives up. */
    @Test
    void throwsOnAPayloadMissingWhatAReportGroupsBy() {
        Map<String, Object> noCurrency = new HashMap<>();
        noCurrency.put("paymentIntentId", "pi_1");
        noCurrency.put("capturedAmountMinor", 1);

        assertThatThrownBy(() -> ReportFactExtractor.extract("payment.succeeded", noCurrency))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void throwsOnATypeItDoesNotProject() {
        assertThatThrownBy(() -> ReportFactExtractor.extract("order.paid", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("order.paid");
    }

    /**
     * The extractor and the domain must agree on the subscribed set, or a handler could be
     * registered for a type nothing can read -- or a type could be readable and never subscribed.
     */
    @Test
    void canExtractExactlyTheSubscribedTypes() {
        for (String eventType : ReportFact.SUBSCRIBED_TYPES) {
            assertThat(ReportFactExtractor.canExtract(eventType)).as(eventType).isTrue();
        }

        assertThat(ReportFactExtractor.canExtract("order.paid")).isFalse();
    }
}
