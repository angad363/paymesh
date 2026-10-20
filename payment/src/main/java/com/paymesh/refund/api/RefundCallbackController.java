package com.paymesh.refund.api;

import com.paymesh.refund.application.RecordRefundCallbackCommand;
import com.paymesh.refund.application.RecordRefundCallbackService;
import com.paymesh.refund.domain.RefundEvent;
import com.paymesh.refund.domain.RefundOutcome;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * {@code POST /internal/v1/refund-callbacks/{provider}} -- how a provider reports a refund result.
 *
 * <h2>REFUND'S OWN ROUTE, NOT A BRANCH OF PAYMENT'S (ADR-019)</h2>
 *
 * The alternative was a {@code REFUNDED} member on Payment's {@code ProviderOutcome} and a refund
 * id on its callback. That is fewer files and it puts Refund's vocabulary inside Payment: Payment
 * would have to know refunds exist in order to route the callback, and the arrow between the two
 * modules would point both ways in the one direction that is hardest to undo.
 * <p>
 * The cost of the separate route is one thing: the HMAC filter had to move to {@code shared} so
 * that two routes could use one implementation rather than two copies of the check standing between
 * a forged request and a merchant's money.
 *
 * <h2>Authentication is the signature and nothing else</h2>
 *
 * The path is {@code permitAll()} on the Spring chain, because a provider holds no PayMesh token.
 * It must not be reachable with a MERCHANT's token either -- this route decides that money went
 * back, and a merchant able to call it could settle their own refunds.
 */
@RestController
@RequestMapping("internal/v1/refund-callbacks")
public final class RefundCallbackController {

    /** Where the signature filter publishes the raw-body hash. Read back by the handler below. */
    public static final String PAYLOAD_HASH_ATTRIBUTE = "paymesh.refund.callback.payloadHash";

    private final RecordRefundCallbackService recordRefundCallbackService;

    public RefundCallbackController(RecordRefundCallbackService recordRefundCallbackService) {
        this.recordRefundCallbackService = recordRefundCallbackService;
    }

    @PostMapping("/{provider}")
    RefundCallbackResponse record(
        @PathVariable String provider,
        @Valid @RequestBody RefundCallbackRequest request,
        @RequestAttribute(PAYLOAD_HASH_ATTRIBUTE) String payloadHash
    ) {
        return RefundCallbackResponse.of(recordRefundCallbackService.record(
            new RecordRefundCallbackCommand(
                provider.trim().toUpperCase(Locale.ROOT),
                new RefundEvent(
                    request.eventId(),
                    request.occurredAt(),
                    request.refundId(),
                    request.providerReference(),
                    RefundOutcome.parse(request.outcome()),
                    request.failureCode(),
                    request.failureMessage()
                ),
                payloadHash
            )
        ));
    }
}
