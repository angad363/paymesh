package com.paymesh.simulator.api;

import com.paymesh.simulator.application.CaptureSimulatedPaymentService;
import com.paymesh.simulator.application.ConfigureFailureProfileService;
import com.paymesh.simulator.application.CreateSimulatedPaymentCommand;
import com.paymesh.simulator.application.CreateSimulatedPaymentService;
import com.paymesh.simulator.application.CreateSimulatedRefundCommand;
import com.paymesh.simulator.application.CreateSimulatedRefundService;
import com.paymesh.simulator.application.ExportReconciliationService;
import com.paymesh.simulator.application.SimulatedPaymentResult;
import com.paymesh.simulator.domain.SimulatedBehaviour;
import com.paymesh.simulator.domain.SimulatedCaptureMethod;
import com.paymesh.simulator.domain.SimulatedMethod;
import com.paymesh.simulator.domain.SimulatedPaymentId;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;

/**
 * THE PROVIDER'S API, NOT PAYMESH'S.
 *
 * <h2>Read the path prefix before anything else</h2>
 *
 * {@code /sim/v1/**}, not {@code /api/v1/**}. This is not the merchant API, it must never appear in
 * merchant documentation or in a merchant folder of the Postman collection, and no merchant bearer
 * token is a way in. {@code POST /sim/v1/payments} enqueues a callback that will mark a PayMesh
 * payment SUCCEEDED, so a merchant able to reach it could authorize their own collection -- the
 * exact compromise {@code ProviderCallbackSignatureFilter} exists to prevent. The route is
 * {@code permitAll()} on the Spring chain for the same reason the callback route is: there is no
 * bearer token to evaluate, and {@code SimulatorApiKeyFilter} is the authentication.
 *
 * <h2>No merchant, no tenant, anywhere in this file</h2>
 *
 * A provider serves one API credential and has no idea PayMesh has tenants. There is no
 * {@code AuthenticatedCaller} argument on any method, no {@code MerchantId}, and no table behind
 * these routes carries a {@code merchant_id}. If a tenant ever appears here, the boundary SDD 13.2
 * draws has been crossed.
 *
 * <h2>Idempotency is the module's own, and it is in the body</h2>
 *
 * {@code IdempotencyFilter} is not in this path and must not be: it keys on
 * {@code merchant + endpoint + key}, and the merchant comes from a verified bearer token that does
 * not exist here. {@code uq_provider_payments_idempotency_key} is the guard instead.
 */
@RestController
@RequestMapping("sim/v1")
public final class SimulatorController {

    private final CreateSimulatedPaymentService createSimulatedPaymentService;
    private final CaptureSimulatedPaymentService captureSimulatedPaymentService;
    private final CreateSimulatedRefundService createSimulatedRefundService;
    private final com.paymesh.simulator.application.CreateSimulatedPayoutService createSimulatedPayoutService;
    private final ExportReconciliationService exportReconciliationService;
    private final ConfigureFailureProfileService configureFailureProfileService;

    public SimulatorController(
        CreateSimulatedPaymentService createSimulatedPaymentService,
        CaptureSimulatedPaymentService captureSimulatedPaymentService,
        CreateSimulatedRefundService createSimulatedRefundService,
        com.paymesh.simulator.application.CreateSimulatedPayoutService createSimulatedPayoutService,
        ExportReconciliationService exportReconciliationService,
        ConfigureFailureProfileService configureFailureProfileService
    ) {
        this.createSimulatedPaymentService = createSimulatedPaymentService;
        this.captureSimulatedPaymentService = captureSimulatedPaymentService;
        this.createSimulatedRefundService = createSimulatedRefundService;
        this.createSimulatedPayoutService = createSimulatedPayoutService;
        this.exportReconciliationService = exportReconciliationService;
        this.configureFailureProfileService = configureFailureProfileService;
    }

    /**
     * 201 on a create, <b>200 on a replay</b>, and the difference is the whole reason this method
     * returns a {@code ResponseEntity} while the others do not. A caller retrying a create can tell
     * which happened from the status line alone, without diffing bodies -- and a test asserting that
     * one row exists after two identical posts asserts on the status rather than on a count query.
     */
    @PostMapping("payments")
    ResponseEntity<SimulatedPaymentResponse> createPayment(
        @Valid @RequestBody CreateSimulatedPaymentRequest request
    ) {
        SimulatedPaymentResult result = createSimulatedPaymentService.create(
            new CreateSimulatedPaymentCommand(
                request.idempotencyKey(),
                request.callbackReference(),
                SimulatedMethod.parse(request.method()),
                request.token(),
                request.amountMinor(),
                request.currency(),
                request.captureMethod() == null
                    ? SimulatedCaptureMethod.AUTOMATIC
                    : SimulatedCaptureMethod.parse(request.captureMethod())
            )
        );

        return ResponseEntity
            .status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(SimulatedPaymentResponse.from(result.payment()));
    }

    /**
     * Settles an authorization and enqueues the SUCCEEDED callback.
     * <p>
     * Only legal from AUTHORIZED, which is the state a MANUAL payment stops in. An AUTOMATIC payment
     * is already CAPTURED and answers 409 -- calling this on one would be asking the provider to
     * collect twice.
     */
    @PostMapping("payments/{providerPaymentId}/capture")
    SimulatedPaymentResponse capturePayment(
        @PathVariable String providerPaymentId,
        @Valid @RequestBody CaptureSimulatedPaymentRequest request
    ) {
        return SimulatedPaymentResponse.from(captureSimulatedPaymentService.capture(
            SimulatedPaymentId.from(providerPaymentId),
            request.amountMinor()
        ));
    }

    /**
     * 201 whether the issuer honoured it or not. A DECLINED refund is still a refund the provider
     * recorded and will report in reconciliation; it is not a failure of the request, and returning
     * 4xx for it would make "the issuer said no" indistinguishable from "your request was wrong".
     */
    /**
     * A payout, SDD 13.3, and the gap ADR-017 recorded as "no consumer" now that Settlement is one.
     *
     * <p>201 on a create and <b>200 on a resubmission</b>, like {@code POST /sim/v1/payments} and
     * for the same reason: the caller retrying can tell which happened from the status line without
     * diffing bodies. A resubmission queues no second callback -- see the service.
     */
    @PostMapping("payouts")
    ResponseEntity<SimulatedPayoutResponse> createPayout(
        @Valid @RequestBody CreateSimulatedPayoutRequest request
    ) {
        com.paymesh.simulator.application.CreateSimulatedPayoutService.Result result =
            createSimulatedPayoutService.create(
                new com.paymesh.simulator.application.CreateSimulatedPayoutCommand(
                    request.externalReference(),
                    request.destination(),
                    request.amountMinor(),
                    request.currency()
                )
            );

        return ResponseEntity
            .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(SimulatedPayoutResponse.from(result.payout()));
    }

    @PostMapping("refunds")
    @ResponseStatus(HttpStatus.CREATED)
    SimulatedRefundResponse createRefund(@Valid @RequestBody CreateSimulatedRefundRequest request) {
        return SimulatedRefundResponse.from(createSimulatedRefundService.refund(
            new CreateSimulatedRefundCommand(
                request.idempotencyKey(),
                SimulatedPaymentId.from(request.providerPaymentId()),
                request.callbackReference(),
                request.amountMinor()
            )
        ));
    }

    /**
     * The provider's own truth for one UTC day. An input for the reconciliation job ADR-015 leans
     * on; not that job.
     * <p>
     * A malformed date is a 400 from {@code MethodArgumentTypeMismatchException} before this method
     * runs. A date in the future is not an error -- it is an empty report, which is the honest
     * answer.
     */
    @GetMapping("reconciliation/{date}")
    ReconciliationResponse reconciliation(
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ReconciliationResponse.from(exportReconciliationService.export(date));
    }

    /** Last-write-wins configuration, so 200 and not 201: there is one row and it always existed. */
    @PostMapping("failure-profile")
    FailureProfileResponse configureFailureProfile(
        @Valid @RequestBody ConfigureFailureProfileRequest request
    ) {
        return FailureProfileResponse.from(configureFailureProfileService.configure(
            SimulatedBehaviour.parse(request.defaultBehaviour()),
            Duration.ofMillis(request.callbackDelayMs())
        ));
    }
}
