package com.paymesh.customer.api;

import com.paymesh.customer.application.CreateCustomerCommand;
import com.paymesh.customer.application.CreateCustomerService;
import com.paymesh.customer.application.GetCustomerService;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers are merchant-scoped, and the merchant is taken from the access token on every route.
 * No method here reads a tenant from a path, a query parameter or a body, which is what makes
 * cross-tenant access impossible rather than merely unlikely.
 */
@RestController
@RequestMapping("api/v1/customers")
public final class CustomerController {

    private final CreateCustomerService createCustomerService;
    private final GetCustomerService getCustomerService;

    public CustomerController(
        CreateCustomerService createCustomerService,
        GetCustomerService getCustomerService
    ) {
        this.createCustomerService = createCustomerService;
        this.getCustomerService = getCustomerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CustomerResponse create(
        @Valid @RequestBody CreateCustomerRequest request,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchant();

        Customer customer = createCustomerService.create(
            new CreateCustomerCommand(
                merchantId,
                request.merchantReference(),
                request.email(),
                request.name(),
                request.phone()
            )
        );

        return CustomerResponse.from(customer);
    }

    @GetMapping("/{customerId}")
    CustomerResponse getById(@PathVariable String customerId, AuthenticatedCaller caller) {
        Customer customer = getCustomerService.getById(
            caller.requireSingleMerchant(),
            CustomerId.from(customerId)
        );

        return CustomerResponse.from(customer);
    }
}
