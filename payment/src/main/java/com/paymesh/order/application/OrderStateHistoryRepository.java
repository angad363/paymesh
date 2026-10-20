package com.paymesh.order.application;

import com.paymesh.order.domain.OrderStateChange;

/**
 * Append-only writes to an order's timeline.
 * <p>
 * One method, and no read: nothing exposes a timeline yet, and a query with no caller would be a
 * guess about what a future endpoint wants. The rows are written from this PR anyway, because a
 * history that starts halfway through is worthless -- which is precisely the debt V11 records and
 * cannot repay for the orders that already exist.
 * <p>
 * Like {@code OutboxWriter}, {@code append} assumes a transaction is already open and must not
 * start one. A state-history row that committed while the transition it describes rolled back would
 * record something that never happened.
 */
public interface OrderStateHistoryRepository {

    void append(OrderStateChange change);
}
