package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.refund.domain.RefundStateChange;
import com.paymesh.refund.domain.RefundStatus;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The over-refund arithmetic, against hand-written repositories -- no Spring, no database.
 * <p>
 * This proves the READABLE half of the rule. The enforced half is
 * {@code tr_refunds_within_captured}, exercised by {@code RefundIntegrationTest} with the
 * application entirely out of the path.
 */
class CreateRefundServiceTest {

    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final String PAYMENT = "pi_00000000-0000-0000-0000-000000000001";
    private static final Instant NOW = Instant.parse("2026-08-02T11:00:00Z");

    private final FakeRefunds refunds = new FakeRefunds();
    private final FakeHistory history = new FakeHistory();
    private final FakePayments payments = new FakePayments();
    private final FakeOutbox outbox = new FakeOutbox();

    private final CreateRefundService service = new CreateRefundService(
        refunds, history, payments, outbox,
        // A TransactionTemplate needs a manager to open a transaction; these tests only need the
        // callback executed, so a template with no manager runs it inline and commits nothing.
        new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        },
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void createsARefundAlreadySubmittedToTheProvider() {
        payments.captured(99900, "INR");

        Refund refund = service.create(command(30000L));

        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.amountMinor()).isEqualTo(30000);
        assertThat(refund.paymentIntentId()).isEqualTo(PAYMENT);
    }

    /**
     * THE CURRENCY COMES FROM THE PAYMENT, and the request has no field for it. A refund in a
     * currency the payment was not collected in would compare bare integers across currencies.
     */
    @Test
    void denominatesTheRefundInThePaymentsCurrency() {
        payments.captured(99900, "JPY");

        assertThat(service.create(command(500L)).currency()).isEqualTo("JPY");
    }

    /** PENDING then PROCESSING, both recorded, so the timeline shows the provider hand-off. */
    @Test
    void writesBothTransitionsToTheTimeline() {
        payments.captured(99900, "INR");

        service.create(command(30000L));

        assertThat(history.changes).hasSize(2);
        assertThat(history.changes.get(0).toStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(history.changes.get(0).actorType())
            .isEqualTo(RefundStateChange.ActorType.MERCHANT);
        assertThat(history.changes.get(1).toStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(history.changes.get(1).actorType())
            .isEqualTo(RefundStateChange.ActorType.SYSTEM);
    }

    @Test
    void announcesRefundCreated() {
        payments.captured(99900, "INR");

        service.create(command(30000L));

        assertThat(outbox.events).hasSize(1);
        assertThat(outbox.events.get(0).eventType()).isEqualTo("refund.created");
        assertThat(outbox.events.get(0).merchantId()).isEqualTo(MERCHANT);
    }

    // --- the full-refund default ----------------------------------------------------------------

    /** No amount means the whole capture, when nothing has been refunded yet. */
    @Test
    void refundsTheWholeCaptureWhenNoAmountIsGiven() {
        payments.captured(99900, "INR");

        assertThat(service.create(command(null)).amountMinor()).isEqualTo(99900);
    }

    /**
     * NO AMOUNT MEANS WHAT IS LEFT, NOT WHAT WAS CAPTURED.
     * <p>
     * Reading it as the captured total would fail the over-refund check every time against a
     * partly-refunded payment -- making the convenience useless exactly when it is most wanted.
     */
    @Test
    void refundsOnlyTheRemainderWhenSomethingIsAlreadySpokenFor() {
        payments.captured(99900, "INR");
        refunds.existing(60000, RefundStatus.SUCCEEDED);

        assertThat(service.create(command(null)).amountMinor()).isEqualTo(39900);
    }

    // --- over-refund ----------------------------------------------------------------------------

    @Test
    void refusesMoreThanWasCaptured() {
        payments.captured(99900, "INR");

        assertThatThrownBy(() -> service.create(command(100000L)))
            .isInstanceOf(RefundExceedsCapturedAmountException.class)
            .hasMessageContaining("99900");
    }

    /** The sum matters, not the individual amount: two legal halves that overshoot together. */
    @Test
    void refusesARefundThatOvershootsOnlyWhenAddedToAnExistingOne() {
        payments.captured(99900, "INR");
        refunds.existing(60000, RefundStatus.SUCCEEDED);

        assertThatThrownBy(() -> service.create(command(60000L)))
            .isInstanceOf(RefundExceedsCapturedAmountException.class);
    }

    /**
     * A REFUND STILL IN FLIGHT IS SPOKEN FOR. Counting only SUCCEEDED would let a merchant queue
     * ten full refunds while the first is with the provider, each individually valid.
     */
    @Test
    void countsAnInFlightRefundAgainstTheCapturedAmount() {
        payments.captured(99900, "INR");
        refunds.existing(99900, RefundStatus.PROCESSING);

        assertThatThrownBy(() -> service.create(command(1L)))
            .isInstanceOf(RefundExceedsCapturedAmountException.class);
    }

    /** A failed refund releases its amount: no money moved and none will. */
    @Test
    void ignoresAFailedRefundWhenComputingHeadroom() {
        payments.captured(99900, "INR");
        refunds.existing(99900, RefundStatus.FAILED);

        assertThat(service.create(command(99900L)).amountMinor()).isEqualTo(99900);
    }

    @Test
    void ignoresACancelledRefundWhenComputingHeadroom() {
        payments.captured(99900, "INR");
        refunds.existing(99900, RefundStatus.CANCELLED);

        assertThat(service.create(command(99900L)).amountMinor()).isEqualTo(99900);
    }

    /**
     * Asking for a full refund of a fully-refunded payment. The remainder is zero, and without the
     * explicit guard the aggregate would throw a bare "amount must be positive" that says nothing
     * about refunds.
     */
    @Test
    void refusesAFullRefundWhenNothingIsLeft() {
        payments.captured(99900, "INR");
        refunds.existing(99900, RefundStatus.SUCCEEDED);

        assertThatThrownBy(() -> service.create(command(null)))
            .isInstanceOf(RefundExceedsCapturedAmountException.class);
    }

    /** Exactly the remainder is allowed; the rule is <=, not <. */
    @Test
    void allowsARefundOfExactlyWhatIsLeft() {
        payments.captured(99900, "INR");
        refunds.existing(60000, RefundStatus.SUCCEEDED);

        assertThat(service.create(command(39900L)).amountMinor()).isEqualTo(39900);
    }

    // --- what is not refundable -----------------------------------------------------------------

    @Test
    void refusesAPaymentThatDoesNotExistOrIsNotTheirs() {
        payments.none();

        assertThatThrownBy(() -> service.create(command(100L)))
            .isInstanceOf(PaymentNotRefundableException.class);
    }

    /** Same answer, so nothing distinguishes "not yours" from "collected nothing". */
    @Test
    void refusesAPaymentThatIsNotInARefundableState() {
        payments.notRefundable();

        assertThatThrownBy(() -> service.create(command(100L)))
            .isInstanceOf(PaymentNotRefundableException.class);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static CreateRefundCommand command(Long amountMinor) {
        return new CreateRefundCommand(
            MERCHANT, PAYMENT, amountMinor, null, "Customer changed their mind", "usr_1"
        );
    }

    private static final class FakePayments implements PaymentLookup {

        private RefundablePayment payment;

        void captured(long capturedMinor, String currency) {
            payment = new RefundablePayment(PAYMENT, capturedMinor, currency, true);
        }

        void notRefundable() {
            payment = new RefundablePayment(PAYMENT, 0, "INR", false);
        }

        void none() {
            payment = null;
        }

        @Override
        public Optional<RefundablePayment> findRefundable(MerchantId merchantId, String id) {
            return Optional.ofNullable(payment);
        }
    }

    private static final class FakeRefunds implements RefundRepository {

        private final Map<String, Refund> saved = new LinkedHashMap<>();
        private long existingActiveTotal;

        void existing(long amountMinor, RefundStatus status) {
            if (status.countsAgainstCapturedAmount()) {
                existingActiveTotal += amountMinor;
            }
        }

        @Override
        public Refund save(Refund refund) {
            saved.put(refund.refundId().value(), refund);
            return refund;
        }

        @Override
        public Optional<Refund> findByRefundId(MerchantId merchantId, RefundId refundId) {
            return Optional.ofNullable(saved.get(refundId.value()));
        }

        @Override
        public Optional<Refund> findForUpdate(RefundId refundId) {
            return Optional.ofNullable(saved.get(refundId.value()));
        }

        @Override
        public List<Refund> findPage(MerchantId merchantId, RefundCursor cursor, int limit) {
            return List.copyOf(saved.values());
        }

        @Override
        public long activeTotalMinor(String paymentIntentId) {
            return existingActiveTotal;
        }
    }

    private static final class FakeHistory implements RefundStateHistoryRepository {

        private final List<RefundStateChange> changes = new ArrayList<>();

        @Override
        public void append(RefundStateChange change) {
            changes.add(change);
        }
    }

    private static final class FakeOutbox implements OutboxWriter {

        private final List<OutboxEvent> events = new ArrayList<>();

        @Override
        public void append(OutboxEvent event) {
            events.add(event);
        }
    }
}
