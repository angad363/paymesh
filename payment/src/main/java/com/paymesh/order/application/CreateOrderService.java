package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class CreateOrderService {

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int ORDER_CREATED_VERSION = 1;

    private final OrderRepository orderRepository;
    private final OrderStateHistoryRepository history;
    private final CustomerLookup customers;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreateOrderService(
        OrderRepository orderRepository,
        OrderStateHistoryRepository history,
        CustomerLookup customers,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.history = history;
        this.customers = customers;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Order create(CreateOrderCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Create Order Command cannot be null");
        }

        Instant now = Instant.now(clock);

        Order order = Order.create(
            OrderId.generate(),
            command.merchantId(),
            command.customerId(),
            command.merchantOrderReference(),
            command.amountMinor(),
            command.currency(),
            command.description(),
            command.metadata(),
            command.expiresAt(),
            now
        );

        // Read both optional values back off the aggregate rather than the command: the domain
        // trimmed them and turned blank into null, so these are the values that will actually reach
        // the database.
        if (order.customerId() != null
            && !customers.exists(order.merchantId(), order.customerId())) {
            throw new CustomerNotFoundForOrderException(order.customerId());
        }

        String merchantOrderReference = order.merchantOrderReference();

        if (merchantOrderReference != null
            && orderRepository.existsByMerchantOrderReference(order.merchantId(), merchantOrderReference)) {
            throw new OrderReferenceAlreadyExistsException(merchantOrderReference);
        }

        // THE TRANSACTION BOUNDARY, AND IT IS THE POINT (ADR-010). The order, its first timeline row
        // and the event announcing it are one fact: an order that committed without its event would
        // be invisible to every consumer forever, and an event without its order would announce
        // something that never happened. Both reads above are deliberately outside -- they take no
        // locks and holding a transaction open across them buys nothing.
        //
        // Forgetting this wrap compiles and passes every happy-path test. That is the accepted cost
        // of TransactionTemplate over @Transactional, and it is what
        // OutboxTransactionIntegrationTest exists to catch.
        return transactions.execute(status -> {
            Order saved = orderRepository.save(order);

            // NULL -> PENDING. The creation row, written here rather than inferred from created_at
            // later, so that every order's timeline reads the same way: one row per transition,
            // starting at the one that brought it into existence. The history row goes AFTER the
            // save because fk_order_state_history_order needs the order to exist first.
            history.append(new OrderStateChange(
                saved.merchantId(),
                saved.orderId(),
                null,
                saved.status(),
                OrderStateChange.ActorType.MERCHANT,
                saved.merchantId().value(),
                null,
                now
            ));

            outbox.append(orderCreated(saved, now));
            return saved;
        });
    }

    private static OutboxEvent orderCreated(Order order, Instant occurredAt) {
        // HashMap, not Map.of: customerId and merchantOrderReference are legitimately absent on a
        // guest checkout, and Map.of rejects a null value. They are carried as explicit JSON nulls
        // rather than dropped, so a consumer reads the same shape for every order.
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.orderId().value());
        payload.put("merchantId", order.merchantId().value());
        payload.put("customerId", order.customerId());
        payload.put("amountMinor", order.amountMinor());
        payload.put("currency", order.currency());
        payload.put("merchantOrderReference", order.merchantOrderReference());
        payload.put("status", order.status().name());
        payload.put("createdAt", order.createdAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            order.merchantId(),
            "ORDER",
            order.orderId().value(),
            "order.created",
            ORDER_CREATED_VERSION,
            payload,
            occurredAt
        );
    }
}
