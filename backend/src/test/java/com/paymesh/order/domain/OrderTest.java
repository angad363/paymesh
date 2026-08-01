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

    // --- expiry ------------------------------------------------------------------

    /**
     * PENDING to EXPIRED, and the row does NOT borrow the cancellation columns.
     * {@code ck_orders_cancellation} refuses a {@code cancelled_at} on any status but CANCELLED, and
     * an expiry is not a cancellation -- {@code expires_at} already says why and when.
     */
    @Test
    void expiresAPendingOrderWhoseDeadlineHasPassed() {
        Instant expiresAt = CREATED_AT.plusSeconds(3600);
        Order expired = orderExpiringAt(expiresAt).expire(expiresAt.plusSeconds(1));

        assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.updatedAt()).isEqualTo(expiresAt.plusSeconds(1));
        assertThat(expired.expiresAt()).isEqualTo(expiresAt);
        assertThat(expired.cancelledAt()).isNull();
        assertThat(expired.cancellationReason()).isNull();
    }

    /** At the deadline exactly is expired. The comparison is at-or-after, not strictly after. */
    @Test
    void expiresAnOrderExactlyAtItsDeadline() {
        Instant expiresAt = CREATED_AT.plusSeconds(3600);

        assertThat(orderExpiringAt(expiresAt).expire(expiresAt).status())
            .isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void refusesToExpireAnOrderWhoseDeadlineHasNotArrived() {
        Instant expiresAt = CREATED_AT.plusSeconds(3600);

        assertThatThrownBy(() -> orderExpiringAt(expiresAt).expire(expiresAt.minusSeconds(1)))
            .isInstanceOf(OrderNotExpirableException.class);
    }

    /**
     * NO DEADLINE MEANS NEVER, and this is the one that would be catastrophic rather than merely
     * wrong. {@code expiresAt} is optional; treating null as "already past" would kill every
     * open-ended order on the platform the first time the sweeper ran.
     */
    @Test
    void refusesToExpireAnOrderThatSetNoDeadline() {
        assertThatThrownBy(() -> order(1999, "INR").expire(CREATED_AT.plusSeconds(999_999)))
            .isInstanceOf(OrderNotExpirableException.class);
    }

    /** A finished order stays finished. Expiring it would overwrite how it actually ended. */
    @Test
    void refusesToExpireAnOrderThatIsNoLongerPending() {
        Instant expiresAt = CREATED_AT.plusSeconds(3600);
        Order cancelled = orderExpiringAt(expiresAt).cancel("changed my mind", CREATED_AT.plusSeconds(60));

        assertThatThrownBy(() -> cancelled.expire(expiresAt.plusSeconds(1)))
            .isInstanceOf(OrderNotExpirableException.class);
    }

    /** A second expiry has nothing left to do and must say so rather than restamp the first. */
    @Test
    void refusesASecondExpiry() {
        Instant expiresAt = CREATED_AT.plusSeconds(3600);
        Order expired = orderExpiringAt(expiresAt).expire(expiresAt.plusSeconds(1));

        assertThatThrownBy(() -> expired.expire(expiresAt.plusSeconds(2)))
            .isInstanceOf(OrderNotExpirableException.class);
    }

    @Test
    void refusesToExpireWithNoTimestamp() {
        assertThatThrownBy(() -> orderExpiringAt(CREATED_AT.plusSeconds(3600)).expire(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code hasExpiredBy} is the sweeper's skip predicate and {@code expire} is the guard. They must
     * agree on every case, or the sweeper catches an exception as control flow -- or worse, skips
     * something the aggregate would have allowed.
     */
    @Test
    void answersHasExpiredByExactlyWhereExpireWouldSucceed() {
        Instant expiresAt = CREATED_AT.plusSeconds(3600);
        Order pending = orderExpiringAt(expiresAt);

        assertThat(pending.hasExpiredBy(expiresAt.minusSeconds(1))).isFalse();
        assertThat(pending.hasExpiredBy(expiresAt)).isTrue();
        assertThat(pending.hasExpiredBy(expiresAt.plusSeconds(1))).isTrue();
        assertThat(order(1999, "INR").hasExpiredBy(expiresAt.plusSeconds(1))).isFalse();
        assertThat(pending.expire(expiresAt).hasExpiredBy(expiresAt.plusSeconds(1))).isFalse();
        assertThat(pending.cancel(null, CREATED_AT.plusSeconds(1)).hasExpiredBy(expiresAt)).isFalse();
    }

    /** Cancelling an already-expired order is refused: the state machine has one exit per state. */
    @Test
    void refusesToCancelAnExpiredOrder() {
        Instant expiresAt = CREATED_AT.plusSeconds(3600);
        Order expired = orderExpiringAt(expiresAt).expire(expiresAt);

        assertThatThrownBy(() -> expired.cancel("too late", expiresAt.plusSeconds(60)))
            .isInstanceOf(OrderNotCancellableException.class);
    }

    private static Order orderExpiringAt(Instant expiresAt) {
        return Order.create(
            OrderId.generate(),
            MerchantId.generate(),
            null,
            null,
            1999,
            "INR",
            null,
            Map.of(),
            expiresAt,
            CREATED_AT
        );
    }
}
