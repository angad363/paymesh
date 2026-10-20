package com.paymesh.order.api;

import com.paymesh.order.application.CancelOrderService;
import com.paymesh.order.application.CreateOrderCommand;
import com.paymesh.order.application.CreateOrderService;
import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.ListOrdersService;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Orders are merchant-scoped, and the merchant is taken from the access token on every route.
 * No method here reads a tenant from a path, a query parameter or a body, which is what makes
 * cross-tenant access impossible rather than merely unlikely.
 * <p>
 * Both writes are registered in {@code IdempotencyConfiguration}, so a retry of either is safe.
 */
@RestController
@RequestMapping("api/v1/orders")
public final class OrderController {

    private final CreateOrderService createOrderService;
    private final GetOrderService getOrderService;
    private final ListOrdersService listOrdersService;
    private final CancelOrderService cancelOrderService;

    public OrderController(
        CreateOrderService createOrderService,
        GetOrderService getOrderService,
        ListOrdersService listOrdersService,
        CancelOrderService cancelOrderService
    ) {
        this.createOrderService = createOrderService;
        this.getOrderService = getOrderService;
        this.listOrdersService = listOrdersService;
        this.cancelOrderService = cancelOrderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse create(@Valid @RequestBody CreateOrderRequest request, AuthenticatedCaller caller) {
        MerchantId merchantId = caller.requireSingleMerchant();

        Order order = createOrderService.create(new CreateOrderCommand(
            merchantId,
            request.customerId(),
            request.merchantOrderReference(),
            request.amountMinor(),
            request.currency(),
            request.description(),
            request.metadata() == null ? Map.of() : request.metadata(),
            request.expiresAt()
        ));

        return OrderResponse.from(order);
    }

    @GetMapping("/{orderId}")
    OrderResponse getById(@PathVariable String orderId, AuthenticatedCaller caller) {
        return OrderResponse.from(
            getOrderService.getById(caller.requireSingleMerchant(), OrderId.from(orderId))
        );
    }

    @GetMapping
    OrderPageResponse list(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) String status,
        AuthenticatedCaller caller
    ) {
        return OrderPageResponse.from(listOrdersService.list(
            caller.requireSingleMerchant(),
            status == null ? null : OrderStatus.parse(status),
            cursor,
            limit
        ));
    }

    /**
     * Cancellation is requested as an action, never by writing a status field (SDD; the state
     * machine belongs to the aggregate). The body is optional: a merchant may cancel without
     * giving a reason.
     */
    @PostMapping("/{orderId}/cancel")
    OrderResponse cancel(
        @PathVariable String orderId,
        @Valid @RequestBody(required = false) CancelOrderRequest request,
        AuthenticatedCaller caller
    ) {
        return OrderResponse.from(cancelOrderService.cancel(
            caller.requireSingleMerchant(),
            OrderId.from(orderId),
            request == null ? null : request.reason()
        ));
    }
}
