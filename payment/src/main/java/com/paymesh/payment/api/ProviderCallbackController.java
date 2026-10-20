package com.paymesh.payment.api;

import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Where a provider reports what happened. The seam the Provider Simulator will plug into, and the
 * one a reconciliation replay will use as well.
 * <p>
 * <b>NOT under {@code /api/}, and that is a boundary rather than a naming preference.</b> This is not
 * the public merchant surface: it takes no bearer token, it belongs in no merchant folder of the
 * Postman collection, and it must not appear in public API docs. It is also deliberately absent from
 * {@code IdempotentRoutes} -- that layer keys on merchant + endpoint + Idempotency-Key from a
 * verified merchant token, and a provider has none of the three. {@code provider_callbacks} is this
 * endpoint's deduplication and the two must not be confused.
 * <p>
 * <b>The controller decides nothing.</b> It parses, and hands one command to one service. The
 * transition logic lives in {@code RecordProviderCallbackService} so that the reconciliation job
 * this design does not build can call the same code with the same idempotent effect (design section
 * 0.4). A controller holding the logic would have forced that job to reimplement it, and two
 * implementations of "what does this event mean" is how a payment gets applied twice.
 */
@RestController
@RequestMapping("internal/v1/provider-callbacks")
public final class ProviderCallbackController {

    /**
     * Where the signature filter leaves the raw body's SHA-256 for this controller to pick up.
     * <p>
     * A literal, because {@code @RequestAttribute} needs a compile-time constant -- and declared
     * HERE rather than on the filter so that {@code api} does not import {@code infrastructure}. The
     * dependency runs inward; the filter reads this constant, not the other way round.
     */
    public static final String PAYLOAD_HASH_ATTRIBUTE = "paymesh.provider.callback.payloadHash";

    private final RecordProviderCallbackService recordProviderCallbackService;

    public ProviderCallbackController(RecordProviderCallbackService recordProviderCallbackService) {
        this.recordProviderCallbackService = recordProviderCallbackService;
    }

    /**
     * 200 FOR EVERY OUTCOME, INCLUDING THE REFUSALS. Duplicated, superseded and terminal deliveries
     * all answer 200 with an outcome naming what happened, because a provider retries on any non-2xx
     * and answering a finished payment with a 409 is an infinite retry loop -- a self-inflicted
     * outage that looks like a provider problem (ADR-012).
     * <p>
     * The one exception is a callback naming an intent this platform does not know: 404, nothing
     * stored, and the retry it provokes is exactly what should happen, because the likeliest cause is
     * a callback overtaking the transaction that created the intent.
     *
     * @param provider    from the PATH, not the body. A body claiming a different provider than the
     *                    route it arrived on would be a second, weaker identity for a value the
     *                    deduplication key depends on. Uppercased so {@code simulator} and
     *                    {@code SIMULATOR} cannot become two deduplication namespaces for one
     *                    provider.
     * @param payloadHash set by the signature filter, which is the last place the raw bytes exist.
     *                    Its presence also proves the filter ran: without it the request never
     *                    passed signature verification, and Spring answers 400 rather than letting
     *                    an unverified body reach the service.
     */
    @PostMapping("/{provider}")
    ProviderCallbackResponse record(
        @PathVariable String provider,
        @Valid @RequestBody ProviderCallbackRequest request,
        @RequestAttribute(PAYLOAD_HASH_ATTRIBUTE) String payloadHash
    ) {
        return ProviderCallbackResponse.of(recordProviderCallbackService.record(
            new RecordProviderCallbackCommand(
                provider.trim().toUpperCase(Locale.ROOT),
                new ProviderEvent(
                    request.eventId(),
                    request.occurredAt(),
                    request.paymentIntentId(),
                    request.providerReference(),
                    ProviderOutcome.parse(request.outcome()),
                    request.authorizedAmountMinor(),
                    request.capturedAmountMinor(),
                    request.failureCode(),
                    request.failureMessage(),
                    request.actionUrl()
                ),
                payloadHash
            )
        ));
    }
}
