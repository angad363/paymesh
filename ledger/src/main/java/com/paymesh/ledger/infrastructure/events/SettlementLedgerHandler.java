package com.paymesh.ledger.infrastructure.events;

import com.paymesh.ledger.application.PostSettlementJournalsService;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Ledger's three subscriptions to Settlement, in one class because they differ by exactly one
 * thing: which journal to post.
 *
 * <h2>ONE CLASS, THREE BEANS -- AND WHY THAT IS NOT THE USUAL SHAPE HERE</h2>
 *
 * Every other consumer in this codebase is one class per event type, because each reads a different
 * payload. These three read the SAME payload -- a batch, an amount, a currency -- and choose a
 * posting from the event's name. Three near-identical classes would be three places for the payload
 * reading to drift, and drift between them is not a compile error: it is one settlement journal
 * built from a field the other two do not read.
 * <p>
 * The consumer name is still one per event type, which is what {@code processed_events} dedups on.
 * Sharing a name across the three would make the first event delivered swallow the other two.
 *
 * <h2>Imports nothing from Settlement</h2>
 *
 * Payload as a {@code Map}, merchant from the envelope, exactly like the payment and refund
 * handlers. {@code ModuleBoundaryTest} keeps the Ledger's allowlist for Settlement at one file, and
 * this is not it.
 */
public final class SettlementLedgerHandler implements EventHandler {

    private final String consumerName;
    private final String eventType;
    private final Consumer<Journal> posting;

    private SettlementLedgerHandler(
        String consumerName, String eventType, Consumer<Journal> posting
    ) {
        this.consumerName = consumerName;
        this.eventType = eventType;
        this.posting = posting;
    }

    /** {@code settlement.batch_cut} -- available becomes in-transit. */
    public static SettlementLedgerHandler batchCut(PostSettlementJournalsService journals) {
        return new SettlementLedgerHandler(
            "ledger.settlement-batch-cut",
            "settlement.batch_cut",
            journal -> journals.postBatchCut(
                journal.merchantId(), journal.settlementBatchId(), journal.amountMinor(),
                journal.currency(), journal.occurredAt()
            )
        );
    }

    /** {@code payout.paid} -- in-transit is discharged against PayMesh's cash. */
    public static SettlementLedgerHandler payoutPaid(PostSettlementJournalsService journals) {
        return new SettlementLedgerHandler(
            "ledger.payout-paid",
            "payout.paid",
            journal -> journals.postPayoutPaid(
                journal.merchantId(), journal.settlementBatchId(), journal.amountMinor(),
                journal.currency(), journal.occurredAt()
            )
        );
    }

    /** {@code payout.returned} -- in-transit goes back to available, as a new journal. */
    public static SettlementLedgerHandler payoutReturned(PostSettlementJournalsService journals) {
        return new SettlementLedgerHandler(
            "ledger.payout-returned",
            "payout.returned",
            journal -> journals.postPayoutReturned(
                journal.merchantId(), journal.settlementBatchId(), journal.amountMinor(),
                journal.currency(), journal.occurredAt()
            )
        );
    }

    @Override
    public String consumerName() {
        return consumerName;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(OutboxEvent event) {
        Map<String, Object> payload = event.payload();

        posting.accept(new Journal(
            event.merchantId(),
            requireText(payload, "settlementBatchId"),
            requireAmount(payload),
            requireText(payload, "currency"),
            occurredAt(payload, event)
        ));
    }

    private Instant occurredAt(Map<String, Object> payload, OutboxEvent event) {
        Object value = payload.get("occurredAt");

        return value == null ? event.occurredAt() : Instant.parse(value.toString());
    }

    /** Through {@link Number}: JSONB hands back an Integer for anything that fits in 32 bits. */
    private long requireAmount(Map<String, Object> payload) {
        Object value = payload.get("amountMinor");

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(eventType + " carries no numeric amountMinor");
        }

        return number.longValue();
    }

    private String requireText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(eventType + " carries no " + key);
        }

        return value.toString();
    }

    /** The payload all three events share, read once. */
    private record Journal(
        com.paymesh.shared.tenant.MerchantId merchantId,
        String settlementBatchId,
        long amountMinor,
        String currency,
        Instant occurredAt
    ) {
    }
}
