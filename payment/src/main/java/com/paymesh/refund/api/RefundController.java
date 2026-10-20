package com.paymesh.refund.api;

import com.paymesh.refund.application.CancelRefundService;
import com.paymesh.refund.application.CreateRefundCommand;
import com.paymesh.refund.application.CreateRefundService;
import com.paymesh.refund.application.GetRefundService;
import com.paymesh.refund.application.ListRefundsService;
import com.paymesh.refund.domain.RefundId;
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

/**
 * SDD 16.3's merchant-facing refund API, under {@code /api/v1} rather than the doc's {@code /v1} --
 * matching the code, as CLAUDE.md prescribes when the two disagree.
 * <p>
 * The tenant is derived from the verified token on every route. No request record carries a
 * {@code merchantId}, so a cross-tenant write is not something the API can express.
 */
@RestController
@RequestMapping("api/v1/refunds")
public final class RefundController {

    private final CreateRefundService createRefundService;
    private final GetRefundService getRefundService;
    private final ListRefundsService listRefundsService;
    private final CancelRefundService cancelRefundService;

    public RefundController(
        CreateRefundService createRefundService,
        GetRefundService getRefundService,
        ListRefundsService listRefundsService,
        CancelRefundService cancelRefundService
    ) {
        this.createRefundService = createRefundService;
        this.getRefundService = getRefundService;
        this.listRefundsService = listRefundsService;
        this.cancelRefundService = cancelRefundService;
    }

    /** Registered as an idempotent route, so the filter requires an {@code Idempotency-Key}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RefundResponse create(@Valid @RequestBody CreateRefundRequest request, AuthenticatedCaller caller) {
        MerchantId merchantId = caller.requireSingleMerchant();

        return RefundResponse.from(createRefundService.create(new CreateRefundCommand(
            merchantId,
            request.paymentIntentId(),
            request.amountMinor(),
            request.merchantReference(),
            request.reason(),
            caller.userId()
        )));
    }

    @GetMapping("/{refundId}")
    RefundResponse get(@PathVariable String refundId, AuthenticatedCaller caller) {
        return RefundResponse.from(
            getRefundService.getById(caller.requireSingleMerchant(), RefundId.from(refundId))
        );
    }

    @GetMapping
    RefundPageResponse list(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String cursor,
        AuthenticatedCaller caller
    ) {
        return RefundPageResponse.from(
            listRefundsService.list(caller.requireSingleMerchant(), cursor, limit)
        );
    }

    /**
     * Cancellation is requested as an action, never by writing a status field. The window is narrow
     * and the 409 is the common answer -- see {@code CancelRefundService} for why that is correct
     * rather than a defect.
     */
    @PostMapping("/{refundId}/cancel")
    RefundResponse cancel(@PathVariable String refundId, AuthenticatedCaller caller) {
        return RefundResponse.from(cancelRefundService.cancel(
            caller.requireSingleMerchant(), RefundId.from(refundId), caller.userId()
        ));
    }
}
