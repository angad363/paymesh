package com.paymesh.order.infrastructure.persistence.jpa;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderJpaMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-31T10:15:30Z");

    @Test
    void roundTripsEveryFieldThroughTheRowAndBack() {
        Order order = Order.create(
            OrderId.generate(),
            MerchantId.generate(),
            "cus_11111111-1111-4111-8111-111111111111",
            "ORDER-7788",
            1999,
            "INR",
            "Two masala chai",
            Map.of("channel", "web"),
            CREATED_AT.plusSeconds(3600),
            CREATED_AT
        );

        Order mapped = OrderJpaMapper.toDomain(OrderJpaMapper.toEntity(order));

        assertThat(mapped).usingRecursiveComparison().isEqualTo(order);
    }

    @Test
    void roundTripsNullOptionalFields() {
        Order order = Order.create(
            OrderId.generate(), MerchantId.generate(), null, null, 1999, "INR", null, Map.of(), null,
            CREATED_AT
        );

        Order mapped = OrderJpaMapper.toDomain(OrderJpaMapper.toEntity(order));

        assertThat(mapped).usingRecursiveComparison().isEqualTo(order);
        assertThat(mapped.customerId()).isNull();
    }

    /**
     * Absent metadata reaches the column as SQL NULL rather than an empty JSON object, so "sent
     * none" and "sent {}" do not become two spellings of the same thing -- and it still comes back
     * as an empty map, because the domain does not hand out nulls for collections.
     */
    @Test
    void storesAbsentMetadataAsNullAndReadsItBackAsAnEmptyMap() {
        Order order = Order.create(
            OrderId.generate(), MerchantId.generate(), null, null, 1999, "INR", null, Map.of(), null,
            CREATED_AT
        );

        assertThat(OrderJpaMapper.toEntity(order).metadata()).isNull();
        assertThat(OrderJpaMapper.toDomain(OrderJpaMapper.toEntity(order)).metadata()).isEmpty();
    }

    @Test
    void writesTheStatusAsItsNameNotItsOrdinal() {
        Order cancelled = Order.create(
            OrderId.generate(), MerchantId.generate(), null, null, 1999, "INR", null, Map.of(), null,
            CREATED_AT
        ).cancel("out of stock", CREATED_AT.plusSeconds(60));

        assertThat(OrderJpaMapper.toEntity(cancelled).status()).isEqualTo(OrderStatus.CANCELLED.name());
    }

    /** A never-saved order carries no version, which is how the adapter knows to INSERT. */
    @Test
    void carriesANullVersionForAnOrderThatHasNeverBeenPersisted() {
        Order order = Order.create(
            OrderId.generate(), MerchantId.generate(), null, null, 1999, "INR", null, Map.of(), null,
            CREATED_AT
        );

        assertThat(OrderJpaMapper.toEntity(order).version()).isNull();
    }
}
