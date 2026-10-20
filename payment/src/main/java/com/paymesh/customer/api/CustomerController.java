package com.paymesh.customer.api;

import com.paymesh.customer.application.CreateCustomerCommand;
import com.paymesh.customer.application.AttachPaymentMethodTokenService;
import com.paymesh.customer.application.ChangeCustomerStatusService;
import com.paymesh.customer.application.UpdateCustomerService;
import com.paymesh.customer.domain.PaymentMethodTokenId;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.customer.application.CreateCustomerService;
import com.paymesh.customer.application.GetCustomerService;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
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
import java.util.List;

@RestController
@RequestMapping("api/v1/customers")
public final class CustomerController {

    private final CreateCustomerService createCustomerService;
    private final GetCustomerService getCustomerService;
    private final UpdateCustomerService updateCustomerService;
    private final ChangeCustomerStatusService changeCustomerStatusService;
    private final AttachPaymentMethodTokenService attachPaymentMethodTokenService;

    public CustomerController(
        CreateCustomerService createCustomerService,
        GetCustomerService getCustomerService,
        UpdateCustomerService updateCustomerService,
        ChangeCustomerStatusService changeCustomerStatusService,
        AttachPaymentMethodTokenService attachPaymentMethodTokenService
    ) {
        this.createCustomerService = createCustomerService;
        this.getCustomerService = getCustomerService;
        this.updateCustomerService = updateCustomerService;
        this.changeCustomerStatusService = changeCustomerStatusService;
        this.attachPaymentMethodTokenService = attachPaymentMethodTokenService;
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

    /** SDD 10.3's PATCH. Contact details change; a record that cannot be corrected forces a duplicate. */
    @PatchMapping("/{customerId}")
    CustomerResponse update(
        @PathVariable String customerId,
        @Valid @RequestBody UpdateCustomerRequest request,
        AuthenticatedCaller caller
    ) {
        return CustomerResponse.from(updateCustomerService.update(
            caller.requireSingleMerchant(),
            CustomerId.from(customerId),
            request.email(),
            request.name(),
            request.phone()
        ));
    }

    /**
     * BLOCKED WAS DECLARED IN V3 AND NOTHING COULD PRODUCE IT. ADR-021 added the aggregate method
     * and claimed the state was reachable; no service or endpoint ever called it. This is the
     * endpoint that makes the claim true (ADR-023).
     * <p>
     * MERCHANT_ADMIN, because refusing to sell to someone is not a day-to-day operational act.
     */
    @PostMapping("/{customerId}/block")
    CustomerResponse block(
        @PathVariable String customerId,
        @Valid @RequestBody(required = false) BlockCustomerRequest request,
        AuthenticatedCaller caller
    ) {
        return CustomerResponse.from(changeCustomerStatusService.block(
            caller.requireSingleMerchantWith(CallerRole.MERCHANT_ADMIN),
            CustomerId.from(customerId),
            caller.userId(),
            request == null ? null : request.reason()
        ));
    }

    @PostMapping("/{customerId}/unblock")
    CustomerResponse unblock(@PathVariable String customerId, AuthenticatedCaller caller) {
        return CustomerResponse.from(changeCustomerStatusService.unblock(
            caller.requireSingleMerchantWith(CallerRole.MERCHANT_ADMIN),
            CustomerId.from(customerId),
            caller.userId()
        ));
    }

    // --- payment methods ----------------------------------------------------------------------

    /**
     * SDD 10.3, and the first thing that ever wrote to {@code payment_method_tokens} -- a table
     * that has existed since V3 with no writer (ADR-023).
     */
    @PostMapping("/{customerId}/payment-methods")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentMethodTokenResponse attachPaymentMethod(
        @PathVariable String customerId,
        @Valid @RequestBody AttachPaymentMethodTokenRequest request,
        AuthenticatedCaller caller
    ) {
        return PaymentMethodTokenResponse.from(attachPaymentMethodTokenService.attach(
            caller.requireSingleMerchant(),
            CustomerId.from(customerId),
            request.provider(),
            request.providerToken(),
            request.fingerprint(),
            request.brand(),
            request.lastFour(),
            request.expiryMonth(),
            request.expiryYear()
        ));
    }

    @GetMapping("/{customerId}/payment-methods")
    List<PaymentMethodTokenResponse> listPaymentMethods(
        @PathVariable String customerId,
        AuthenticatedCaller caller
    ) {
        return attachPaymentMethodTokenService
            .list(caller.requireSingleMerchant(), CustomerId.from(customerId))
            .stream()
            .map(PaymentMethodTokenResponse::from)
            .toList();
    }

    /** Detach is a timestamp, not a delete -- see {@code PaymentMethodToken}. */
    @DeleteMapping("/{customerId}/payment-methods/{tokenId}")
    PaymentMethodTokenResponse detachPaymentMethod(
        @PathVariable String customerId,
        @PathVariable String tokenId,
        AuthenticatedCaller caller
    ) {
        return PaymentMethodTokenResponse.from(attachPaymentMethodTokenService.detach(
            caller.requireSingleMerchant(), PaymentMethodTokenId.from(tokenId)
        ));
    }
}
