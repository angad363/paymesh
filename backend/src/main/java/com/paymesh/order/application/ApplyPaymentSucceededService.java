package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * What a successful payment does to the order it was collected against.
 *
 * <h2>Why this is not in the handler</h2>
 *
 * {@code PaymentSucceededHandler} is the consumer: it reads an untyped {@code Map<String, Object>}
 * out of an event envelope, which is a TRANSPORT concern and belongs in {@code infrastructure}.
 * Deciding what a collection does to an obligation is a RULE, and it belongs here, where it is
 * exercised by calling one method with plain arguments -- no Spring, no database, no event, no
 * relay. The same split {@code OrderExpirySweeper} / {@code ExpireOrdersService} already uses.
 *
 * <h2>Order still does not know Payment exists, and this class is where that is easiest to break</h2>
 *
 * The arguments are a {@link MerchantId}, an {@link OrderId}, a {@code long} and an {@link Instant}.
 * No {@code PaymentIntent}, no {@code PaymentIntentStatus}, nothing importable from
 * {@code com.paymesh.payment} -- {@code ModuleBoundaryTest.orderNeverImportsPayment} has an EMPTY
 * allowlist and this change had to keep it that way. Order learns that a payment succeeded the same
 * way another service would: from an event.
 *
 * <h2>IT DOES NOT OPEN A TRANSACTION, AND THAT IS DELIBERATE</h2>
 *
 * Every other application service in this codebase takes a {@code TransactionTemplate} (ADR-010).
 * This one must not: it runs inside the transaction {@code EventDispatcher} opened, which already
 * holds the {@code processed_events} row claiming this event. A transaction of its own would commit
 * independently of that row, and a crash between the two would double-apply the payment on
 * redelivery -- the exact failure the inbox exists to make impossible.
 */
public final class ApplyPaymentSucceededService {

    private static final Logger log = LoggerFactory.getLogger(ApplyPaymentSucceededService.class);

    /** SDD 22.1. Bump only when a payload below stops being readable by an existing consumer. */
    private static final int ORDER_PAID_VERSION = 1;

    /** Stored on the timeline row. There is no principal behind a consumer, hence no actor id. */
    private static final String REASON = "A payment against this order succeeded";

    private final OrderRepository orderRepository;
    private final OrderStateHistoryRepository history;
    private final GetOrderService getOrderService;
    private final OutboxWriter outbox;

    public ApplyPaymentSucceededService(
        OrderRepository orderRepository,
        OrderStateHistoryRepository history,
        GetOrderService getOrderService,
        OutboxWriter outbox
    ) {
        this.orderRepository = orderRepository;
        this.history = history;
        this.getOrderService = getOrderService;
        this.outbox = outbox;
    }

    /**
     * Records the collection against the order.
     *
     * @param capturedAmountMinor how much the payment actually took. Compared against the ORDER'S
     *                            amount to decide PAID versus PARTIALLY_PAID -- see
     *                            {@link Order#markPaid}
     * @param occurredAt          when the collecting authority says it happened
     * @return true if the order moved, false if it was in no state to
     */
    public boolean apply(
        MerchantId merchantId,
        OrderId orderId,
        long capturedAmountMinor,
        Instant occurredAt
    ) {
        // LOCKED, for the reason the expiry sweep locks (ADR-014 section 3): a merchant cancelling
        // this order concurrently must serialize with this, not race it. Both paths take the same
        // row, so either the cancel wins and the guard below refuses, or this wins and the cancel
        // finds a PAID order its own state machine refuses.
        Order order = getOrderService.getByIdForUpdate(merchantId, orderId);

        // THE AGGREGATE-LEVEL IDEMPOTENCY GUARD, and it is a QUERY rather than a caught exception so
        // an ordinary re-delivery is not logged as a failure. Order.markPaid enforces the same rule
        // for every caller; a predicate the aggregate does not itself honour is a comment, not a rule.
        if (order.status() != OrderStatus.PENDING) {
            log.info(
                "Payment succeeded against an order that is already {} orderId={} merchantId={}",
                order.status(), orderId.value(), merchantId.value()
            );

            return false;
        }

        OrderStatus from = order.status();
        Order saved = orderRepository.save(order.markPaid(capturedAmountMinor, occurredAt));

        history.append(new OrderStateChange(
            saved.merchantId(),
            saved.orderId(),
            from,
            saved.status(),
            // SYSTEM, with a null actorId. V11 predicted this exact row: "when order.status finally
            // moves on a payment it will be SYSTEM -- an Order-owned consumer of payment.succeeded
            // acting on Order's own table -- not the provider reaching across the boundary."
            OrderStateChange.ActorType.SYSTEM,
            null,
            REASON,
            occurredAt
        ));

        outbox.append(orderPaid(saved, from, occurredAt));

        return true;
    }

    /**
     * SDD 22.1 names {@code order.paid}. {@code order.partially_paid} is its counterpart, and the two
     * are separate event types rather than one carrying a status: a consumer subscribing to
     * "this order is settled" must not have to inspect a payload to discover it is not.
     * <p>
     * Emitted under the rule the Payment PRs settled and {@code ExpireOrdersService} states: a
     * transition that changes what a consumer would believe gets an event, in the transition's own
     * transaction. Nothing consumes these two today -- the relay dispatches them to an empty handler
     * list and stamps them published, which is the correct handling of an event nobody wants.
     */
    private static OutboxEvent orderPaid(Order order, OrderStatus from, Instant occurredAt) {
        // HashMap, not Map.of: the customer and the merchant's reference are both legitimately
        // absent and Map.of rejects a null value. They are carried as explicit JSON nulls rather
        // than dropped, so a consumer reads the same shape every time.
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.orderId().value());
        payload.put("merchantId", order.merchantId().value());
        payload.put("customerId", order.customerId());
        payload.put("merchantOrderReference", order.merchantOrderReference());
        payload.put("amountMinor", order.amountMinor());
        payload.put("amountPaidMinor", order.amountPaidMinor());
        payload.put("currency", order.currency());
        payload.put("previousStatus", from.name());
        payload.put("status", order.status().name());
        payload.put("occurredAt", occurredAt.toString());

        return new OutboxEvent(
            EventId.generate(),
            order.merchantId(),
            "ORDER",
            order.orderId().value(),
            order.status() == OrderStatus.PAID ? "order.paid" : "order.partially_paid",
            ORDER_PAID_VERSION,
            payload,
            occurredAt
        );
    }
}
