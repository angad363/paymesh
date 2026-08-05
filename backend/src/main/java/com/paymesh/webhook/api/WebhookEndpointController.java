package com.paymesh.webhook.api;

import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.webhook.application.ListWebhookDeliveriesService;
import com.paymesh.webhook.application.RegisterWebhookEndpointCommand;
import com.paymesh.webhook.application.RegisterWebhookEndpointService;
import com.paymesh.webhook.application.ReplayWebhookDeliveryService;
import com.paymesh.webhook.application.RotateWebhookSecretService;
import com.paymesh.webhook.application.UpdateWebhookEndpointCommand;
import com.paymesh.webhook.application.UpdateWebhookEndpointService;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SDD §18.3's five routes, under {@code /api/v1} rather than the doc's {@code /v1} -- matching the
 * code, as CLAUDE.md prescribes when the two disagree.
 *
 * <p>The tenant comes from the verified token on every route. No request record carries a
 * {@code merchantId}, so a cross-tenant write is not something this API can express.
 *
 * <h2>TWO OF THESE ROUTES ARE DELIBERATELY NOT IDEMPOTENT-FILTERED</h2>
 *
 * Create and rotate return a secret, and {@code idempotency_records.response_body} persists response
 * bodies verbatim so a retry can replay them -- registering either route would write the secret to
 * the database in cleartext, one table away from the storage ADR-028 exists to avoid. Rotate is
 * idempotent on its own terms instead ({@code fromVersion}).
 * <p>
 * <b>Create is not idempotent</b>, and the recovery path is rotation rather than retry: a second
 * create at the same URL answers 409 with no secret, because handing the secret back to whoever
 * POSTs an existing URL would turn create into "reveal this endpoint's secret". Only
 * {@code POST .../replay} is on {@code IdempotentRoutes}.
 */
@RestController
@RequestMapping("api/v1/webhook-endpoints")
public final class WebhookEndpointController {

    private final RegisterWebhookEndpointService registerWebhookEndpointService;
    private final UpdateWebhookEndpointService updateWebhookEndpointService;
    private final RotateWebhookSecretService rotateWebhookSecretService;
    private final ListWebhookDeliveriesService listWebhookDeliveriesService;
    private final ReplayWebhookDeliveryService replayWebhookDeliveryService;

    public WebhookEndpointController(
        RegisterWebhookEndpointService registerWebhookEndpointService,
        UpdateWebhookEndpointService updateWebhookEndpointService,
        RotateWebhookSecretService rotateWebhookSecretService,
        ListWebhookDeliveriesService listWebhookDeliveriesService,
        ReplayWebhookDeliveryService replayWebhookDeliveryService
    ) {
        this.registerWebhookEndpointService = registerWebhookEndpointService;
        this.updateWebhookEndpointService = updateWebhookEndpointService;
        this.rotateWebhookSecretService = rotateWebhookSecretService;
        this.listWebhookDeliveriesService = listWebhookDeliveriesService;
        this.replayWebhookDeliveryService = replayWebhookDeliveryService;
    }

    /** The one response that carries a secret for the first time. Shown once; rotate to recover it. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WebhookEndpointResponse register(
        @Valid @RequestBody RegisterWebhookEndpointRequest request, AuthenticatedCaller caller
    ) {
        return WebhookEndpointResponse.from(registerWebhookEndpointService.register(
            caller.requireSingleMerchant(),
            new RegisterWebhookEndpointCommand(request.url(), request.subscriptions())
        ));
    }

    @PatchMapping("/{endpointId}")
    WebhookEndpointResponse update(
        @PathVariable String endpointId,
        @Valid @RequestBody UpdateWebhookEndpointRequest request,
        AuthenticatedCaller caller
    ) {
        return WebhookEndpointResponse.from(updateWebhookEndpointService.update(
            caller.requireSingleMerchant(),
            EndpointId.from(endpointId),
            new UpdateWebhookEndpointCommand(request.subscriptions(), request.status())
        ));
    }

    /**
     * Rotation is requested as an action naming the version it replaces, never by writing a version
     * field. The previous version keeps signing for the overlap window.
     */
    @PostMapping("/{endpointId}/rotate-secret")
    WebhookEndpointResponse rotateSecret(
        @PathVariable String endpointId,
        @Valid @RequestBody RotateWebhookSecretRequest request,
        AuthenticatedCaller caller
    ) {
        return WebhookEndpointResponse.from(rotateWebhookSecretService.rotate(
            caller.requireSingleMerchant(), EndpointId.from(endpointId), request.fromVersion()
        ));
    }

    @GetMapping("/{endpointId}/deliveries")
    List<WebhookDeliveryResponse> deliveries(
        @PathVariable String endpointId,
        @RequestParam(required = false) Integer limit,
        AuthenticatedCaller caller
    ) {
        return listWebhookDeliveriesService
            .list(caller.requireSingleMerchant(), EndpointId.from(endpointId), limit)
            .stream()
            .map(WebhookDeliveryResponse::from)
            .toList();
    }

    /**
     * Nested under the endpoint so the tenant check has two things to agree on, and so a merchant
     * browsing deliveries replays from where they found them.
     */
    @PostMapping("/{endpointId}/deliveries/{deliveryId}/replay")
    WebhookDeliveryResponse replay(
        @PathVariable String endpointId,
        @PathVariable String deliveryId,
        AuthenticatedCaller caller
    ) {
        WebhookDelivery replayed = replayWebhookDeliveryService.replay(
            caller.requireSingleMerchant(),
            EndpointId.from(endpointId),
            WebhookDeliveryId.from(deliveryId)
        );

        return WebhookDeliveryResponse.from(replayed);
    }
}
