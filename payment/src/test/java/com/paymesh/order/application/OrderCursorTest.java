package com.paymesh.order.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderCursorTest {

    private static final Instant NOW = Instant.parse("2026-07-31T10:15:30Z");

    @Test
    void survivesARoundTrip() {
        OrderCursor cursor = OrderCursor.of(NOW, "ord_11111111-1111-4111-8111-111111111111");

        assertThat(OrderCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    /** Sub-second precision has to survive, or the tiebreak is doing the work of the timestamp. */
    @Test
    void preservesNanosecondPrecision() {
        OrderCursor cursor = OrderCursor.of(NOW.plusNanos(123_456_789), "ord_x");

        assertThat(OrderCursor.decode(cursor.encode()).createdAt())
            .isEqualTo(NOW.plusNanos(123_456_789));
    }

    @Test
    void isOpaqueToTheCaller() {
        String encoded = OrderCursor.of(NOW, "ord_11111111-1111-4111-8111-111111111111").encode();

        assertThat(encoded).doesNotContain("2026").doesNotContain("ord_");
    }

    @Test
    void treatsAnAbsentCursorAsTheFirstPage() {
        assertThat(OrderCursor.decode(null)).isEqualTo(OrderCursor.start());
        assertThat(OrderCursor.decode("  ")).isEqualTo(OrderCursor.start());
    }

    /**
     * The first page must exclude nothing. "Nothing" means nothing a Clock can stamp and Postgres
     * can store, not {@link Instant#MAX} -- that is year 1000000000, which no timestamptz column
     * accepts, so a sentinel above it would buy an impossibility rather than a guarantee.
     */
    @Test
    void placesTheFirstPageAfterEveryPossibleRow() {
        assertThat(OrderCursor.start().isAfter(NOW, "ord_zzz")).isTrue();
        assertThat(OrderCursor.start().isAfter(Instant.parse("9999-12-30T23:59:59Z"), "ord_zzz"))
            .isTrue();
    }

    @Test
    void rejectsAMalformedCursor() {
        assertThrows(IllegalArgumentException.class, () -> OrderCursor.decode("!!!not base64!!!"));
    }

    @Test
    void rejectsACursorThatDecodesToSomethingElse() {
        String notACursor = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> OrderCursor.decode(notACursor));
    }

    // --- the tiebreak ---------------------------------------------------------

    @Test
    void ordersByTimestampFirst() {
        OrderCursor cursor = OrderCursor.of(NOW, "ord_aaa");

        assertThat(cursor.isAfter(NOW.minusSeconds(1), "ord_zzz")).isTrue();
        assertThat(cursor.isAfter(NOW.plusSeconds(1), "ord_aaa")).isFalse();
    }

    /**
     * The whole reason the cursor carries an id: at an identical instant the comparison must still
     * be decisive, and it must exclude the row the cursor names so that row is not handed out twice.
     */
    @Test
    void breaksATieOnTheOrderId() {
        OrderCursor cursor = OrderCursor.of(NOW, "ord_bbb");

        assertThat(cursor.isAfter(NOW, "ord_aaa")).isTrue();
        assertThat(cursor.isAfter(NOW, "ord_ccc")).isFalse();
        assertThat(cursor.isAfter(NOW, "ord_bbb")).as("the cursor row itself is already spent")
            .isFalse();
    }
}
