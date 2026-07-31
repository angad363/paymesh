package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;

import java.time.Clock;
import java.time.Instant;

public final class CreateOrderService {

    private final OrderRepository orderRepository;
    private final CustomerLookup customers;
    private final Clock clock;

    public CreateOrderService(OrderRepository orderRepository, CustomerLookup customers, Clock clock) {
        this.orderRepository = orderRepository;
        this.customers = customers;
        this.clock = clock;
    }

    public Order create(CreateOrderCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Create Order Command cannot be null");
        }

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
            Instant.now(clock)
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

        return orderRepository.save(order);
    }
}
