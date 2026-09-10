package com.paymesh.reporting.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportFactTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-16T10:00:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-16T10:00:05Z");

    @Test
    void holdsWhatAReportNeeds() {
        ReportFact fact = fact("payment.succeeded", 12_500);

        assertThat(fact.eventType()).isEqualTo("payment.succeeded");
        assertThat(fact.amountMinor()).isEqualTo(12_500);
        assertThat(fact.occurredAt()).isEqualTo(OCCURRED);
        assertThat(fact.recordedAt()).isEqualTo(RECORDED);
    }

    /**
     * THE ASSERTION THAT PAIRS WITH THE MIGRATION. {@code ck_report_facts_event_type} names the same
     * six types; a seventh reaching this constructor would be refused by the database anyway, and
     * this turns that into a readable failure at the handler rather than a constraint violation deep
     * in a flush.
     */
    @Test
    void refusesAnEventTypeTheProjectionDoesNotStore() {
        assertThatThrownBy(() -> fact("order.paid", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not project order.paid");
    }

    @Test
    void acceptsEverySubscribedType() {
        for (String eventType : ReportFact.SUBSCRIBED_TYPES) {
            assertThat(fact(eventType, 1).eventType()).isEqualTo(eventType);
        }
    }

    /** The two report subsets must together be exactly what the projection subscribes to. */
    @Test
    void theTwoReportSubsetsPartitionTheSubscribedTypes() {
        assertThat(ReportFact.PAYMENT_TYPES)
            .doesNotContainAnyElementsOf(ReportFact.SETTLEMENT_TYPES);

        assertThat(ReportFact.SUBSCRIBED_TYPES)
            .containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream
                    .concat(ReportFact.PAYMENT_TYPES.stream(), ReportFact.SETTLEMENT_TYPES.stream())
                    .toList()
            );
    }

    /** Direction lives in the event type, so a negative amount is a bug rather than a refund. */
    @Test
    void refusesANegativeAmount() {
        assertThatThrownBy(() -> fact("refund.succeeded", -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    /** A payment can legitimately fail for a zero-amount intent; zero is not an error. */
    @Test
    void acceptsAZeroAmount() {
        assertThat(fact("payment.failed", 0).amountMinor()).isZero();
    }

    @Test
    void requiresTheFieldsAReportGroupsBy() {
        assertThatThrownBy(() -> new ReportFact(
            "evt_" + java.util.UUID.randomUUID(), merchant(), "payment.succeeded",
            "  ", null, "USD", 1, OCCURRED, RECORDED
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject id");

        assertThatThrownBy(() -> new ReportFact(
            "evt_" + java.util.UUID.randomUUID(), merchant(), "payment.succeeded",
            "pi_x", null, null, 1, OCCURRED, RECORDED
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
    }

    private static ReportFact fact(String eventType, long amountMinor) {
        return new ReportFact(
            "evt_" + java.util.UUID.randomUUID(),
            merchant(),
            eventType,
            "pi_" + java.util.UUID.randomUUID(),
            null,
            "USD",
            amountMinor,
            OCCURRED,
            RECORDED
        );
    }

    private static MerchantId merchant() {
        return MerchantId.generate();
    }
}
