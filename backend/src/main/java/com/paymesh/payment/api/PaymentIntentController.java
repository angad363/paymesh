package com.paymesh.payment.api;

import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.CancelPaymentIntentService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.ListPaymentIntentsService;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
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
 * Payment intents are merchant-scoped, and the merchant is taken from the access token on every
 * route. No method here reads a tenant from a path, a query parameter or a body, which is what makes
 * cross-tenant access impossible rather than merely unlikely -- a {@code pi_} in a path authorizes
 * nothing.
 * <p>
 * Every write is registered in {@code IdempotencyConfiguration}, so a retry of any of them is safe.
 * <p>
 * There is no route that sets a status. Callers request actions and the aggregate decides.
 */
@RestController
@RequestMapping("api/v1/payment-intents")
public final class PaymentIntentController {

    private final CreatePaymentIntentService createPaymentIntentService;
    private final GetPaymentIntentService getPaymentIntentService;
    private final ListPaymentIntentsService listPaymentIntentsService;
    private final AttachPaymentMethodService attachPaymentMethodService;
    private final ConfirmPaymentIntentService confirmPaymentIntentService;
    private final CancelPaymentIntentService cancelPaymentIntentService;

    public PaymentIntentController(
        CreatePaymentIntentService createPaymentIntentService,
        GetPaymentIntentService getPaymentIntentService,
        ListPaymentIntentsService listPaymentIntentsService,
        AttachPaymentMethodService attachPaymentMethodService,
        ConfirmPaymentIntentService confirmPaymentIntentService,
        CancelPaymentIntentService cancelPaymentIntentService
    ) {
        this.createPaymentIntentService = createPaymentIntentService;
        this.getPaymentIntentService = getPaymentIntentService;
        this.listPaymentIntentsService = listPaymentIntentsService;
        this.attachPaymentMethodService = attachPaymentMethodService;
        this.confirmPaymentIntentService = confirmPaymentIntentService;
        this.cancelPaymentIntentService = cancelPaymentIntentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PaymentIntentResponse create(
        @Valid @RequestBody CreatePaymentIntentRequest request,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchant();

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId,
            request.orderId(),
            request.customerId(),
            request.amountMinor(),
            request.currency(),
            request.captureMethod() == null ? null : CaptureMethod.parse(request.captureMethod()),
            request.description(),
            request.metadata() == null ? Map.of() : request.metadata()
        ));

        return PaymentIntentResponse.from(intent);
    }

    @GetMapping("/{paymentIntentId}")
    PaymentIntentResponse getById(@PathVariable String paymentIntentId, AuthenticatedCaller caller) {
        return PaymentIntentResponse.from(getPaymentIntentService.getById(
            caller.requireSingleMerchant(), PaymentIntentId.from(paymentIntentId)
        ));
    }

    @GetMapping
    PaymentIntentPageResponse list(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String orderId,
        AuthenticatedCaller caller
    ) {
        return PaymentIntentPageResponse.from(listPaymentIntentsService.list(
            caller.requireSingleMerchant(),
            status == null ? null : PaymentIntentStatus.parse(status),
            orderId,
            cursor,
            limit
        ));
    }

    /**
     * Records which KIND of instrument will be used and moves the intent to REQUIRES_CONFIRMATION.
     * <p>
     * A type, not a token: nothing in PayMesh can mint a token yet, so requiring one would make this
     * endpoint uncallable (design section 3.2). The route is {@code /payment-method} singular because
     * an intent has one.
     */
    @PostMapping("/{paymentIntentId}/payment-method")
    PaymentIntentResponse attachPaymentMethod(
        @PathVariable String paymentIntentId,
        @Valid @RequestBody AttachPaymentMethodRequest request,
        AuthenticatedCaller caller
    ) {
        return PaymentIntentResponse.from(attachPaymentMethodService.attach(
            caller.requireSingleMerchant(),
            PaymentIntentId.from(paymentIntentId),
            PaymentMethodType.parse(request.paymentMethodType())
        ));
    }

    /**
     * Starts the collection: PROCESSING, plus an attempt.
     * <p>
     * 202 AND NOT 200, EVEN THOUGH NOTHING ASYNCHRONOUS IS INVOKED (SDD 12.4). The request is
     * accepted, not completed: the intent is PROCESSING and the outcome is genuinely undecided until
     * a provider callback resolves it. A 200 would tell the caller the work is done, which is the one
     * thing that is certainly not true. Do not "simplify" it.
     */
    @PostMapping("/{paymentIntentId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PaymentIntentResponse confirm(
        @PathVariable String paymentIntentId,
        @Valid @RequestBody(required = false) ConfirmPaymentIntentRequest request,
        AuthenticatedCaller caller
    ) {
        return PaymentIntentResponse.from(confirmPaymentIntentService.confirm(
            new ConfirmPaymentIntentCommand(
                caller.requireSingleMerchant(),
                PaymentIntentId.from(paymentIntentId),
                request == null ? null : request.returnUrl(),
                request == null ? null : request.device()
            )
        ));
    }

    /**
     * Cancellation is requested as an action, never by writing a status field. It is also how the
     * order's live-intent slot is released (ADR-011), so a merchant who wants to start over calls
     * this first.
     */
    @PostMapping("/{paymentIntentId}/cancel")
    PaymentIntentResponse cancel(
        @PathVariable String paymentIntentId,
        @Valid @RequestBody(required = false) CancelPaymentIntentRequest request,
        AuthenticatedCaller caller
    ) {
        return PaymentIntentResponse.from(cancelPaymentIntentService.cancel(
            caller.requireSingleMerchant(),
            PaymentIntentId.from(paymentIntentId),
            request == null ? null : request.reason()
        ));
    }
}
