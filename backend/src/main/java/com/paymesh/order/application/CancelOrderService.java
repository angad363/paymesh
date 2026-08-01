package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class CancelOrderService {

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int ORDER_CANCELLED_VERSION = 1;

    private final OrderRepository orderRepository;
    private final OrderStateHistoryRepository history;
    private final GetOrderService getOrderService;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CancelOrderService(
        OrderRepository orderRepository,
        OrderStateHistoryRepository history,
        GetOrderService getOrderService,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.history = history;
        this.getOrderService = getOrderService;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Requests cancellation. The service does not decide whether it is allowed -- it loads the
     * aggregate and asks, so the state machine has exactly one implementation and a second caller
     * cannot reach a different conclusion.
     * <p>
     * <b>This used to be two unwrapped statements outside any transaction, and it is now three
     * writes inside one</b> -- the order, its timeline row and {@code order.cancelled}. The rule the
     * Payment capability settled applies to Order's transitions too: every transition writes exactly
     * one state-history row and exactly one event, in the transition's own transaction. A timeline
     * missing a transition that happened is worse than no timeline, because it looks complete.
     */
    public Order cancel(MerchantId merchantId, OrderId orderId, String reason) {
        Instant now = Instant.now(clock);

        // THE READ IS INSIDE THE TRANSACTION AND HOLDS A ROW LOCK, matching every payment
        // transition. Two concurrent cancels of one order would otherwise both read PENDING, both
        // decide they may proceed, and collide on the order's optimistic version -- handing the
        // loser a 500 about row counts instead of the 409 the state machine would have given it.
        //
        // It is also the row Payment's create path locks. That is what makes the expiry sweeper's
        // check safe (ADR-014) and it is worth not undoing here: these three writers -- cancel,
        // create-intent and sweep -- all serialize on the same order row.
        return transactions.execute(status -> {
            Order order = getOrderService.getByIdForUpdate(merchantId, orderId);
            OrderStatus from = order.status();
            Order saved = orderRepository.save(order.cancel(reason, now));

            history.append(new OrderStateChange(
                saved.merchantId(),
                saved.orderId(),
                from,
                saved.status(),
                OrderStateChange.ActorType.MERCHANT,
                saved.merchantId().value(),
                saved.cancellationReason(),
                now
            ));

            outbox.append(orderCancelled(saved, from, now));

            return saved;
        });
    }

    /**
     * NOT AN EVENT THE SDD NAMES, and emitted for the reason {@code payment.cancelled} was.
     * <p>
     * A consumer fed only {@code order.created} holds a permanently open order in its read model and
     * never learns otherwise. Cancellation is the transition that ends an order's life, so it is the
     * one a reporting or reconciliation consumer least affords to miss -- and the first consumer
     * anyone writes is the one that would have to discover the gap. The transaction already existed
     * and already had two writes, so the third is nearly free now and awkward once a relay is
     * running.
     */
    private static OutboxEvent orderCancelled(Order order, OrderStatus from, Instant occurredAt) {
        // HashMap, not Map.of: the customer, the merchant's reference and the reason are all
        // legitimately absent and Map.of rejects a null value. They are carried as explicit JSON
        // nulls rather than dropped, so a consumer reads the same shape for every cancellation.
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.orderId().value());
        payload.put("merchantId", order.merchantId().value());
        payload.put("customerId", order.customerId());
        payload.put("merchantOrderReference", order.merchantOrderReference());
        payload.put("amountMinor", order.amountMinor());
        payload.put("currency", order.currency());
        payload.put("previousStatus", from.name());
        payload.put("status", order.status().name());
        payload.put("cancellationReason", order.cancellationReason());
        payload.put("cancelledAt", order.cancelledAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            order.merchantId(),
            "ORDER",
            order.orderId().value(),
            "order.cancelled",
            ORDER_CANCELLED_VERSION,
            payload,
            occurredAt
        );
    }
}
