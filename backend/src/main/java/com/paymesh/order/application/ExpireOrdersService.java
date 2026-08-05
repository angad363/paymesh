package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves orders past their {@code expiresAt} to EXPIRED (ADR-014).
 *
 * <h2>It is a plain object, and the scheduler is somewhere else</h2>
 *
 * There is no {@code @Scheduled} here, no {@code @Component}, and no Spring annotation of any kind.
 * {@code OrderExpirySweeper} owns the timer and does nothing but call {@link #sweep()} and log the
 * result, so every rule below is testable by calling one method with a fixed {@link Clock} and no
 * scheduler in the picture at all. Logic inside a scheduled class can only be tested by waiting.
 *
 * <h2>Tenant-agnostic, and tenant-safe anyway</h2>
 *
 * A timer has no caller, no token and therefore no merchant, so this cannot use the caller-scoped
 * reads the rest of the capability uses -- and inventing a merchant to pass them would be exactly
 * the caller-supplied tenant ADR-007 exists to forbid. {@link OrderRepository#findExpirable} is
 * deliberately the one read in that port without a merchant argument.
 * <p>
 * What makes it safe is that the candidate list decides nothing. The merchant is read OFF each
 * candidate row, and every write that follows -- the locking re-read, the live-intent question, the
 * history row, the event -- is scoped by that merchant. No row is ever touched under a merchant it
 * does not belong to, and a cross-tenant mix-up would have to originate in the database.
 *
 * <h2>Batched, and idempotent</h2>
 *
 * One batch per sweep, so a backlog drains over several runs instead of loading the whole platform
 * into memory. <b>One transaction per order, not one per batch</b>: a lock held across a whole batch
 * would block merchants creating payment intents against orders this sweep may not even touch, and
 * one order failing must not roll back the ones already expired.
 * <p>
 * Running twice writes nothing twice. The eligibility re-check happens under the row lock and reads
 * the aggregate's own {@link Order#hasExpiredBy}, so the second run finds EXPIRED, is not eligible,
 * and returns before writing anything. The candidate query filters on PENDING for the same reason,
 * but that filter is a performance detail -- the re-check is the guarantee, because a row can change
 * between the query and the lock.
 */
public final class ExpireOrdersService {

    private static final Logger log = LoggerFactory.getLogger(ExpireOrdersService.class);

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int ORDER_EXPIRED_VERSION = 1;

    /** Stored on the timeline row. Not a cancellation reason -- the order was never cancelled. */
    private static final String EXPIRY_REASON = "Expired automatically at its expiresAt deadline";

    private final OrderRepository orderRepository;
    private final OrderStateHistoryRepository history;
    private final GetOrderService getOrderService;
    private final PaymentActivityLookup payments;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;

    public ExpireOrdersService(
        OrderRepository orderRepository,
        OrderStateHistoryRepository history,
        GetOrderService getOrderService,
        PaymentActivityLookup payments,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Expiry sweep batch size must be at least 1");
        }

        this.orderRepository = orderRepository;
        this.history = history;
        this.getOrderService = getOrderService;
        this.payments = payments;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One pass. Returns what it did, so the scheduler can log it and a test can assert it without
     * reading the database.
     */
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        // IDENTIFIERS, so there is nothing left to map before the per-item boundary below.
        List<ExpirableOrder> candidates = orderRepository.findExpirable(now, batchSize);

        int expired = 0;
        int held = 0;
        int failed = 0;

        for (ExpirableOrder candidate : candidates) {
            try {
                // PARSED HERE, INSIDE THE TRY. These two calls validate and throw, and a
                // malformed id is a row the database accepts -- neither merchants.merchant_id nor
                // orders.order_id has a format CHECK. Open item 2.
                if (expireOne(
                    MerchantId.from(candidate.merchantId()),
                    OrderId.from(candidate.orderId()),
                    now
                )) {
                    expired++;
                } else {
                    held++;
                }
            } catch (RuntimeException failure) {
                // ONE BAD ROW MUST NOT STOP THE SWEEP. Candidates come back oldest deadline first,
                // so an order that fails every time would otherwise sit at the head of every batch
                // and starve everything behind it -- a single poisoned row silently disabling
                // expiry platform-wide. Counted and logged rather than swallowed; the next sweep
                // tries it again, which is safe because nothing partial survives its rollback.
                failed++;
                log.warn(
                    "Could not expire order orderId={} merchantId={}",
                    candidate.orderId(), candidate.merchantId(), failure
                );
            }
        }

        return new SweepResult(candidates.size(), expired, held, failed);
    }

    /**
     * One order, in its own transaction.
     *
     * @return true if it was expired, false if it turned out not to be eligible
     */
    private boolean expireOne(MerchantId merchantId, OrderId orderId, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // LOCKED, AND THE LOCK IS WHAT CLOSES THE RACE (ADR-014 section 3). The candidate list
            // was read without one, so between the query and here a merchant may have cancelled the
            // order or Payment may have created an intent against it. Payment's create path locks
            // THIS SAME ROW through OrderLookup.findForUpdate, so the two serialize: either Payment
            // wins and the live-intent check below sees its intent, or this wins and Payment's
            // payability re-read sees EXPIRED and answers ORDER_NOT_PAYABLE.
            Order order = getOrderService.getByIdForUpdate(merchantId, orderId);

            // THE IDEMPOTENCY GUARD. A second sweep -- or a second scheduler instance -- finds the
            // order already EXPIRED and stops here, before any write. Without it the run would
            // append a second timeline row and a second event for a transition that happened once.
            if (!order.hasExpiredBy(now)) {
                return false;
            }

            // THE GUARD THIS ADR EXISTS TO PLACE (ADR-014). An order with a live payment intent is
            // being collected against right now, and expiring it would leave that intent holding the
            // slot of an order that can no longer be paid -- no second intent, no confirm, no route
            // back through the API. That is open item 1's shape and it must not be reintroduced from
            // the other direction.
            //
            // Asked through a port Order declares and Payment implements, so Order still does not
            // import Payment. "Live" is Payment's definition, not a copy of it.
            if (payments.hasLivePaymentIntent(merchantId, orderId.value())) {
                return false;
            }

            OrderStatus from = order.status();
            Order saved = orderRepository.save(order.expire(now));

            history.append(new OrderStateChange(
                saved.merchantId(),
                saved.orderId(),
                from,
                saved.status(),
                // SYSTEM, and actorId is null: there is no principal behind a timer. Writing the
                // merchant here would claim the merchant asked for this, which is the opposite of
                // what expiry is.
                OrderStateChange.ActorType.SYSTEM,
                null,
                EXPIRY_REASON,
                now
            ));

            outbox.append(orderExpired(saved, from, now));

            return true;
        }));
    }

    /**
     * NOT AN EVENT THE SDD NAMES, and emitted under the rule the Payment PRs settled: a transition
     * that changes what a consumer would believe gets an event, in the transition's own transaction.
     * <p>
     * It matters more than most. Expiry is the one order transition no human requested, so a
     * consumer that never hears about it has no other way to learn the order died -- there is no
     * request/response it could have observed.
     */
    private static OutboxEvent orderExpired(Order order, OrderStatus from, Instant occurredAt) {
        // HashMap, not Map.of: the customer and the merchant's reference are both legitimately
        // absent and Map.of rejects a null value. They are carried as explicit JSON nulls rather
        // than dropped, so a consumer reads the same shape for every expiry.
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.orderId().value());
        payload.put("merchantId", order.merchantId().value());
        payload.put("customerId", order.customerId());
        payload.put("merchantOrderReference", order.merchantOrderReference());
        payload.put("amountMinor", order.amountMinor());
        payload.put("currency", order.currency());
        payload.put("previousStatus", from.name());
        payload.put("status", order.status().name());
        // The deadline the order set for itself, and the moment the sweeper acted on it. They are
        // genuinely different facts -- the gap between them is however long the sweep interval is --
        // and a consumer reconciling against a merchant's own records needs the first, not just the
        // second.
        payload.put("expiresAt", order.expiresAt().toString());
        payload.put("expiredAt", occurredAt.toString());

        return new OutboxEvent(
            EventId.generate(),
            order.merchantId(),
            "ORDER",
            order.orderId().value(),
            "order.expired",
            ORDER_EXPIRED_VERSION,
            payload,
            occurredAt
        );
    }

    /**
     * What one sweep did.
     *
     * @param examined how many candidates the query returned
     * @param expired  how many actually moved to EXPIRED
     * @param held     how many were skipped: already expired by a concurrent sweep, cancelled
     *                 underneath, or -- the interesting case -- still holding a live payment intent
     * @param failed   how many threw and were logged. Non-zero here is worth an alert; the orders
     *                 are retried on the next sweep
     */
    public record SweepResult(int examined, int expired, int held, int failed) {
    }
}
