package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListOrdersServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T10:15:30Z");

    private final OrderRepository repository = new InMemoryOrderRepository();
    private final ListOrdersService service = new ListOrdersService(repository);

    @Test
    void listsTheCallersOrdersNewestFirst() {
        MerchantId merchantId = MerchantId.generate();
        Order older = repository.save(order(merchantId, NOW.minusSeconds(60)));
        Order newer = repository.save(order(merchantId, NOW));

        OrderPage page = service.list(merchantId, null, null, null);

        assertThat(page.orders()).extracting(Order::orderId)
            .containsExactly(newer.orderId(), older.orderId());
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.limit()).isEqualTo(ListOrdersService.DEFAULT_LIMIT);
    }

    @Test
    void returnsAnEmptyPageWhenTheMerchantHasNoOrders() {
        OrderPage page = service.list(MerchantId.generate(), null, null, null);

        assertThat(page.orders()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    /** The list is as tenant-scoped as the single read; another merchant's rows are not in it. */
    @Test
    void excludesOrdersBelongingToAnotherMerchant() {
        MerchantId merchantId = MerchantId.generate();
        repository.save(order(merchantId, NOW));
        repository.save(order(MerchantId.generate(), NOW));

        assertThat(service.list(merchantId, null, null, null).orders()).hasSize(1);
    }

    @Test
    void filtersByStatus() {
        MerchantId merchantId = MerchantId.generate();
        repository.save(order(merchantId, NOW));
        Order cancelled = repository.save(order(merchantId, NOW.minusSeconds(60)).cancel(null, NOW));

        OrderPage page = service.list(merchantId, OrderStatus.CANCELLED, null, null);

        assertThat(page.orders()).extracting(Order::orderId).containsExactly(cancelled.orderId());
    }

    // --- paging ---------------------------------------------------------------

    @Test
    void reportsAFurtherPageAndHandsBackACursorForIt() {
        MerchantId merchantId = MerchantId.generate();
        repository.save(order(merchantId, NOW));
        repository.save(order(merchantId, NOW.minusSeconds(60)));

        OrderPage first = service.list(merchantId, null, null, 1);

        assertThat(first.orders()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();

        OrderPage second = service.list(merchantId, null, first.nextCursor(), 1);

        assertThat(second.orders()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.orders().get(0).orderId())
            .isNotEqualTo(first.orders().get(0).orderId());
    }

    /**
     * THE BOUNDARY THE TIEBREAK EXISTS FOR. Three orders created at the SAME instant, paged two at
     * a time, so the boundary falls between rows that a timestamp alone cannot tell apart.
     * <p>
     * A cursor of created_at only either skips the third row (with a strict {@code <}, which walks
     * past every row sharing the boundary instant) or repeats the first two (with {@code <=}). Both
     * are silent: the response looks like a perfectly ordinary page. Only counting what came back
     * across every page catches it.
     */
    @Test
    void pagesAcrossABoundaryWhereOrdersShareACreatedAt() {
        MerchantId merchantId = MerchantId.generate();
        Set<OrderId> created = new HashSet<>();

        for (int order = 0; order < 3; order++) {
            created.add(repository.save(order(merchantId, NOW)).orderId());
        }

        List<OrderId> seen = new ArrayList<>();
        String cursor = null;

        do {
            OrderPage page = service.list(merchantId, null, cursor, 2);
            page.orders().forEach(order -> seen.add(order.orderId()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).as("every order exactly once, none skipped, none repeated")
            .hasSize(3)
            .containsExactlyInAnyOrderElementsOf(created);
    }

    // --- limits ---------------------------------------------------------------

    @Test
    void defaultsTheLimitWhenNoneIsGiven() {
        assertThat(service.list(MerchantId.generate(), null, null, null).limit())
            .isEqualTo(ListOrdersService.DEFAULT_LIMIT);
    }

    /** Asking for too much is capped, never unbounded: no caller can request the whole table. */
    @Test
    void capsAnOverlargeLimit() {
        assertThat(service.list(MerchantId.generate(), null, null, 5_000).limit())
            .isEqualTo(ListOrdersService.MAX_LIMIT);
    }

    @Test
    void rejectsALimitBelowOne() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.list(MerchantId.generate(), null, null, 0)
        );
    }

    @Test
    void rejectsAMalformedCursor() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.list(MerchantId.generate(), null, "not-a-cursor!!", null)
        );
    }

    private static Order order(MerchantId merchantId, Instant createdAt) {
        return Order.create(
            OrderId.generate(),
            merchantId,
            null,
            null,
            1999,
            "INR",
            null,
            Map.of(),
            null,
            createdAt
        );
    }
}
