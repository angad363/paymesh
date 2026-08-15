package com.paymesh.settlement.api;

import com.paymesh.settlement.application.RecordPayoutCallbackService;
import com.paymesh.settlement.domain.PayoutOutcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * {@code POST /internal/v1/payout-callbacks/{provider}} -- how a provider reports a payout result.
 *
 * <h2>SETTLEMENT'S OWN ROUTE, ITS OWN SECRET, ITS OWN DEDUP TABLE</h2>
 *
 * The third instance of the shape ADR-019 argued for, and the argument has not changed: folding
 * payouts into Payment's callback would put Settlement's vocabulary inside Payment and make Payment
 * know that payouts exist in order to route them. The HMAC filter itself is shared -- one
 * implementation of the check, three registrations.
 *
 * <h2>A payout PayMesh does not know is a 404, and that is deliberate</h2>
 *
 * ADR-012 §7's reasoning: the likeliest cause is a callback overtaking the transaction that created
 * the row, so a provider should retry rather than be told everything is fine. The alternative --
 * answering 200 for an unknown id -- turns a lost payout into silence.
 */
@RestController
@RequestMapping("internal/v1/payout-callbacks")
public final class PayoutCallbackController {

    /** Where the signature filter publishes the raw-body hash. Read back below. */
    public static final String PAYLOAD_HASH_ATTRIBUTE = "paymesh.payout.callback.payloadHash";

    private final RecordPayoutCallbackService recordPayoutCallback;

    public PayoutCallbackController(RecordPayoutCallbackService recordPayoutCallback) {
        this.recordPayoutCallback = recordPayoutCallback;
    }

    @PostMapping("/{provider}")
    ResponseEntity<PayoutCallbackResponse> record(
        @PathVariable String provider,
        @Valid @RequestBody PayoutCallbackRequest request,
        @RequestAttribute(PAYLOAD_HASH_ATTRIBUTE) String payloadHash
    ) {
        RecordPayoutCallbackService.Outcome outcome = recordPayoutCallback.record(
            provider.trim().toUpperCase(Locale.ROOT),
            request.eventId(),
            request.payoutId(),
            PayoutOutcome.parse(request.outcome()),
            failureText(request),
            request.occurredAt(),
            payloadHash
        );

        HttpStatus status = outcome == RecordPayoutCallbackService.Outcome.UNKNOWN_PAYOUT
            ? HttpStatus.NOT_FOUND
            : HttpStatus.OK;

        return ResponseEntity.status(status).body(PayoutCallbackResponse.of(outcome));
    }

    /** Both fields or neither; a provider that sends only a code still says something useful. */
    private static String failureText(PayoutCallbackRequest request) {
        if (request.failureCode() == null && request.failureMessage() == null) {
            return null;
        }

        return String.join(
            ": ",
            request.failureCode() == null ? "PROVIDER_REFUSED" : request.failureCode(),
            request.failureMessage() == null ? "" : request.failureMessage()
        );
    }
}
