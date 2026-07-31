package com.paymesh.order.application;

import com.paymesh.order.domain.Order;

import java.util.List;

/**
 * One page of orders plus what a caller needs to ask for the next one.
 *
 * @param orders     the page, newest first
 * @param nextCursor the encoded position to resume from, or null when there is nothing after this
 * @param hasMore    whether another page exists; always {@code nextCursor != null}, stated
 *                   separately because the response contract names both
 * @param limit      the limit actually applied, which is not always the one that was asked for
 */
public record OrderPage(List<Order> orders, String nextCursor, boolean hasMore, int limit) {
}
