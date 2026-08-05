package com.paymesh.webhook.infrastructure.schedule;

import com.paymesh.webhook.application.DeliverWebhooksService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else. Copied deliberately from {@code SimulatorCallbackDispatcher}, which
 * is the reviewed shape for this in the codebase.
 *
 * <p>No logic here: anything inside a scheduled method can only be exercised by starting a context
 * and waiting for a clock to tick. Every rule lives in {@link DeliverWebhooksService}, an ordinary
 * object taking an injected {@code Clock}, which tests drive directly.
 *
 * <p>{@code fixedDelay}, so a slow pass is never re-entered. Overlapping passes would still be
 * correct -- {@code FOR UPDATE SKIP LOCKED} means a claimed row is skipped rather than sent twice --
 * but correct and pointless is still pointless.
 */
public final class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final DeliverWebhooksService deliverWebhooks;

    public WebhookDispatcher(DeliverWebhooksService deliverWebhooks) {
        this.deliverWebhooks = deliverWebhooks;
    }

    @Scheduled(
        fixedDelayString = "${paymesh.webhook.dispatch.interval}",
        initialDelayString = "${paymesh.webhook.dispatch.interval}"
    )
    public void dispatch() {
        DeliverWebhooksService.DispatchResult result = deliverWebhooks.dispatch();

        if (result.examined() > 0) {
            log.info(
                "Webhook dispatch examined={} delivered={} retried={} dead={}",
                result.examined(), result.delivered(), result.retried(), result.dead()
            );
        }
    }
}
