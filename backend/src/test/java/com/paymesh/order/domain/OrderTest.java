package com.paymesh.order.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-31T10:15:30Z");

    // --- money ----------------------------------------------------------------

    @Test
    void keepsTheAmountAsAPositiveIntegerInMinorUnits() {
        Order order = order(1999, "inr");

        assertThat(order.amountMinor()).isEqualTo(1999L);
        assertThat(order.currency()).isEqualTo("INR");
    }

    @Test
    void rejectsAZeroAmount() {
        assertThatThrownBy(() -> order(0, "INR"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Amount");
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> order(-1, "INR"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Amount");
    }

    /**
     * An amount beyond any plausible order is far more likely to be a units mistake (major units
     * sent as minor, or a runaway loop) than a real charge, and BIGINT would happily store it.
     */
    @Test
    void rejectsAnAbsurdlyLargeAmount() {
        assertThatThrownBy(() -> order(Order.MAX_AMOUNT_MINOR + 1, "INR"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Amount");
    }

    @Test
    void startsWithNothingPaid() {
        assertThat(order(1999, "INR").amountPaidMinor()).isZero();
    }

    // --- currency -------------------------------------------------------------

    @Test
    void normalizesCurrencyToUppercase() {
        assertThat(order(1999, "  eur  ").currency()).isEqualTo("EUR");
    }

    @Test
    void rejectsACurrencyThatIsNotThreeLetters() {
        assertThatThrownBy(() -> order(1999, "RUPEE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency");
    }

    @Test
    void rejectsANumericCurrency() {
        assertThatThrownBy(() -> order(1999, "356"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency");
    }

    @Test
    void rejectsAMissingCurrency() {
        assertThatThrownBy(() -> order(1999, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency");
    }

    // --- optional fields ------------------------------------------------------

    /**
     * Blank and absent must collapse to the same stored value, or "  " and null become two rows
     * under a unique constraint that treats only one of them as absent.
     */
    @Test
    void treatsABlankMerchantOrderReferenceAsAbsent() {
        assertThat(orderWithReference("   ").merchantOrderReference()).isNull();
    }

    @Test
    void trimsTheMerchantOrderReference() {
        assertThat(orderWithReference("  ORDER-7788  ").merchantOrderReference()).isEqualTo("ORDER-7788");
    }

    @Test
    void rejectsAnOverlongMerchantOrderReference() {
        assertThatThrownBy(() -> orderWithReference("r".repeat(101)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnExpiryThatIsNotAfterCreation() {
        assertThatThrownBy(() -> Order.create(
            OrderId.generate(),
            MerchantId.generate(),
            null,
            null,
            1999,
            "INR",
            null,
            Map.of(),
            CREATED_AT,
            CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Expiry");
    }

    // --- metadata -------------------------------------------------------------

    @Test
    void defaultsMetadataToAnEmptyMapRatherThanNull() {
        assertThat(order(1999, "INR").metadata()).isEmpty();
    }

    @Test
    void rejectsMoreMetadataKeysThanTheCap() {
        Map<String, String> tooMany = new HashMap<>();

        for (int key = 0; key <= Order.MAX_METADATA_ENTRIES; key++) {
            tooMany.put("key-" + key, "value");
        }

        assertThatThrownBy(() -> orderWithMetadata(tooMany))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Metadata");
    }

    @Test
    void rejectsAnOverlongMetadataKey() {
        assertThatThrownBy(() -> orderWithMetadata(Map.of("k".repeat(41), "value")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Metadata");
    }

    @Test
    void rejectsAnOverlongMetadataValue() {
        assertThatThrownBy(() -> orderWithMetadata(Map.of("key", "v".repeat(501))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Metadata");
    }

    /** The caller keeps its own map; mutating it afterwards must not reach the aggregate. */
    @Test
    void copiesTheMetadataItWasGiven() {
        Map<String, String> supplied = new HashMap<>(Map.of("channel", "web"));
        Order order = orderWithMetadata(supplied);

        supplied.put("channel", "tampered");

        assertThat(order.metadata()).containsExactly(Map.entry("channel", "web"));
    }

    // --- state machine --------------------------------------------------------

    @Test
    void startsPending() {
        assertThat(order(1999, "INR").status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void cancelsAPendingOrder() {
        Instant cancelledAt = CREATED_AT.plusSeconds(60);

        Order cancelled = order(1999, "INR").cancel("  buyer changed their mind  ", cancelledAt);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo("buyer changed their mind");
        assertThat(cancelled.cancelledAt()).isEqualTo(cancelledAt);
        assertThat(cancelled.updatedAt()).isEqualTo(cancelledAt);
    }

    @Test
    void cancelsWithoutAReason() {
        assertThat(order(1999, "INR").cancel(null, CREATED_AT).cancellationReason()).isNull();
    }

    /**
     * The second cancel is the one that matters: cancelling is requested, never set, so a repeat
     * has to be refused by the aggregate rather than quietly overwriting the first cancellation.
     */
    @Test
    void refusesToCancelAnOrderThatIsAlreadyCancelled() {
        Order cancelled = order(1999, "INR").cancel(null, CREATED_AT);

        assertThatThrownBy(() -> cancelled.cancel(null, CREATED_AT))
            .isInstanceOf(OrderNotCancellableException.class)
            .hasMessageContaining("CANCELLED");
    }

    @Test
    void refusesToCancelAnOrderThatIsNotPending() {
        Order paid = Order.reconstitute(
            OrderId.generate(),
            MerchantId.generate(),
            null,
            null,
            1999,
            "INR",
            1999,
            OrderStatus.PAID,
            null,
            Map.of(),
            null,
            null,
            null,
            0,
            CREATED_AT,
            CREATED_AT
        );

        assertThatThrownBy(() -> paid.cancel(null, CREATED_AT))
            .isInstanceOf(OrderNotCancellableException.class);
    }

    @Test
    void leavesTheOriginalUntouchedWhenItIsCancelled() {
        Order pending = order(1999, "INR");

        pending.cancel(null, CREATED_AT);

        assertThat(pending.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(pending.cancelledAt()).isNull();
    }

    // --- identity -------------------------------------------------------------

    @Test
    void rejectsAMissingMerchant() {
        assertThatThrownBy(() -> Order.create(
            OrderId.generate(), null, null, null, 1999, "INR", null, Map.of(), null, CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Merchant");
    }

    @Test
    void rejectsAMissingOrderId() {
        assertThatThrownBy(() -> Order.create(
            null, MerchantId.generate(), null, null, 1999, "INR", null, Map.of(), null, CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Order");
    }

    // --- helpers --------------------------------------------------------------

    private static Order order(long amountMinor, String currency) {
        return Order.create(
            OrderId.generate(),
            MerchantId.generate(),
            null,
            null,
            amountMinor,
            currency,
            null,
            Map.of(),
            null,
            CREATED_AT
        );
    }

    private static Order orderWithReference(String merchantOrderReference) {
        return Order.create(
            OrderId.generate(),
            MerchantId.generate(),
            null,
            merchantOrderReference,
            1999,
            "INR",
            null,
            Map.of(),
            null,
            CREATED_AT
        );
    }

    private static Order orderWithMetadata(Map<String, String> metadata) {
        return Order.create(
            OrderId.generate(),
            MerchantId.generate(),
            null,
            null,
            1999,
            "INR",
            null,
            metadata,
            null,
            CREATED_AT
        );
    }
}
