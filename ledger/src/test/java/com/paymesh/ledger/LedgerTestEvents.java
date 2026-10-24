package com.paymesh.ledger;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Builds the events the ledger consumes, as the producers' relays would deliver them. */
public final class LedgerTestEvents {

    public static final Instant AT = Instant.parse("2026-08-02T10:15:30Z");

    private LedgerTestEvents() {
    }

    public static OutboxEvent paymentSucceeded(EventId eventId, MerchantId merchantId, String paymentIntentId, long amountMinor) {
        return paymentSucceeded(eventId, merchantId, paymentIntentId, amountMinor, "INR");
    }

    public static OutboxEvent paymentSucceeded(
        EventId eventId, MerchantId merchantId, String paymentIntentId, long amountMinor, String currency
    ) {
        return new OutboxEvent(
            eventId, merchantId, "PAYMENT_INTENT", paymentIntentId, "payment.succeeded", 1,
            Map.of(
                "paymentIntentId", paymentIntentId,
                "capturedAmountMinor", amountMinor,
                "currency", currency
            ),
            AT
        );
    }

    public static OutboxEvent paymentSucceeded(MerchantId merchantId, String paymentIntentId, long amountMinor) {
        return paymentSucceeded(EventId.generate(), merchantId, paymentIntentId, amountMinor);
    }

    public static OutboxEvent settlementConfigUpdated(EventId eventId, MerchantId merchantId, long seconds, Instant at) {
        return new OutboxEvent(
            eventId, merchantId, "SETTLEMENT_CONFIG", merchantId.value(), "settlement.config.updated", 1,
            Map.of("merchantId", merchantId.value(), "holdingPeriodSeconds", seconds, "occurredAt", at.toString()),
            at
        );
    }

    public static String newPaymentIntentId() {
        return "pi_" + UUID.randomUUID();
    }
}
