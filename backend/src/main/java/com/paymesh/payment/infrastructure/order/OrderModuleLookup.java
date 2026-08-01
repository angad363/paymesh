package com.paymesh.payment.infrastructure.order;

import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.OrderNotFoundException;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.payment.application.OrderLookup;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

/**
 * THE ONLY CLASS IN {@code com.paymesh.payment} THAT IMPORTS {@code com.paymesh.order}, apart from
 * the configuration that constructs it.
 * <p>
 * Everything else in this module depends on the {@link OrderLookup} port instead, which is what
 * makes extracting Order into its own service a change to this file and nothing else (ADR-008).
 * {@code ModuleBoundaryTest} enforces it.
 * <p>
 * The dependency is one-way and stays that way: <b>{@code com.paymesh.order} must never import
 * {@code com.paymesh.payment}</b>. Payment reads Order and writes it never -- not
 * {@code orders.status}, not {@code amount_paid_minor}. Order will move those columns itself by
 * consuming {@code payment.succeeded} when a relay and a consumer exist.
 * <p>
 * The lookup is tenant-scoped through GetOrderService, which answers "not found" for an order
 * belonging to another merchant. Both cases therefore return empty here, and the caller cannot tell
 * "no such order" from "not yours".
 */
public final class OrderModuleLookup implements OrderLookup {

    private final GetOrderService orders;

    public OrderModuleLookup(GetOrderService orders) {
        this.orders = orders;
    }

    @Override
    public Optional<PayableOrder> find(MerchantId merchantId, String orderId) {
        OrderId parsed;

        try {
            parsed = OrderId.from(orderId);
        } catch (IllegalArgumentException exception) {
            // A malformed id cannot name an order, so it gets the same answer as one that names
            // nobody. Reporting it as a distinct kind of error would confirm the id format is the
            // only thing wrong with it.
            return Optional.empty();
        }

        try {
            Order order = orders.getById(merchantId, parsed);

            return Optional.of(new PayableOrder(
                order.orderId().value(),
                order.customerId(),
                order.amountMinor(),
                order.currency(),
                // The single fact Payment needs about the lifecycle. Order's status enum
                // deliberately does not cross the boundary -- if it did, every Order status added
                // later would become Payment's problem.
                order.status() == OrderStatus.PENDING
            ));
        } catch (OrderNotFoundException exception) {
            return Optional.empty();
        }
    }
}
