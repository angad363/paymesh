package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

public final class ListOrdersService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final OrderRepository orderRepository;

    public ListOrdersService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * One page of the caller's own orders, newest first.
     * <p>
     * A limit above {@link #MAX_LIMIT} is capped rather than rejected -- asking for too much is not
     * a mistake worth failing a read over, and an unbounded collection response is not on offer
     * either way. A limit below 1 IS rejected: it cannot be honoured, and silently turning 0 into
     * 20 would hand back a page the caller did not ask for.
     *
     * @param status filters by status when given; null means every status
     * @param cursor the opaque position from a previous page, or null for the first
     */
    public OrderPage list(MerchantId merchantId, OrderStatus status, String cursor, Integer limit) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null");
        }

        int appliedLimit = applyLimit(limit);
        OrderCursor from = OrderCursor.decode(cursor);

        // One more row than the caller asked for. Its existence is the answer to "is there another
        // page?", which beats a second COUNT query that could disagree with the page it describes.
        List<Order> found = orderRepository.findPage(merchantId, status, from, appliedLimit + 1);
        boolean hasMore = found.size() > appliedLimit;
        List<Order> page = hasMore ? found.subList(0, appliedLimit) : found;

        return new OrderPage(page, hasMore ? cursorAfter(page) : null, hasMore, appliedLimit);
    }

    private static int applyLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }

        return Math.min(limit, MAX_LIMIT);
    }

    private static String cursorAfter(List<Order> page) {
        Order last = page.get(page.size() - 1);

        return OrderCursor.of(last.createdAt(), last.orderId().value()).encode();
    }
}
