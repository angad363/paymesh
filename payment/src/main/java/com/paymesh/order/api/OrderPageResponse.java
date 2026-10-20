package com.paymesh.order.api;

import com.paymesh.order.application.OrderPage;

import java.util.List;

/**
 * The collection envelope from conventions section 11: {@code {data, pagination}}, never a bare
 * array. The wrapper is what lets pagination metadata exist at all, and it keeps the response shape
 * from changing with the volume of data in it.
 */
public record OrderPageResponse(List<OrderResponse> data, Pagination pagination) {

    public static OrderPageResponse from(OrderPage page) {
        return new OrderPageResponse(
            page.orders().stream().map(OrderResponse::from).toList(),
            new Pagination(page.limit(), page.nextCursor(), page.hasMore())
        );
    }

    /**
     * @param nextCursor an OPAQUE token. Clients must not parse, modify or construct one -- its
     *                   encoding is the server's business and is free to change.
     */
    public record Pagination(int limit, String nextCursor, boolean hasMore) {
    }
}
