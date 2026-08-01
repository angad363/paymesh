package com.paymesh.order.application;

import com.paymesh.order.application.ExpireOrdersService.SweepResult;
import com.paymesh.order.application.Fakes.ImmediateTransactions;
import com.paymesh.order.application.Fakes.RecordingHistory;
import com.paymesh.order.application.Fakes.RecordingOutbox;
import com.paymesh.order.application.Fakes.StubPaymentActivity;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expiry sweep's rules, in plain JUnit with no scheduler and no database (ADR-014).
 * <p>
 * <b>That this class exists at all is the argument for keeping the logic out of
 * {@code OrderExpirySweeper}.</b> Every rule here -- the live-intent guard, idempotency, the batch
 * bound, tenant handling -- is exercised by calling one method against a fixed {@link Clock}. Had it
 * lived inside the {@code @Scheduled} method, testing it would have meant booting a context and
 * waiting for a tick.
 * <p>
 * What it cannot prove is the RACE. A list does not hold a row still, so "the sweeper and the create
 * path serialize on the order row" is proved in {@code OrderExpiryIntegrationTest} against
 * PostgreSQL, which is the only thing that can arbitrate it.
 */
class ExpireOrdersServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(7200);
    private static final Instant PASSED = NOW.minusSeconds(60);
    private static final Instant NOT_YET = NOW.plusSeconds(60);

    private final OrderRepository repository = new InMemoryOrderRepository();
    private final GetOrderService getOrderService = new GetOrderService(repository);
    private final ImmediateTransactions transactions = new ImmediateTransactions();
    private final RecordingOutbox outbox = new RecordingOutbox(transactions);
    private final RecordingHistory history = new RecordingHistory(transactions);
    private final StubPaymentActivity payments = new StubPaymentActivity();

    private final ExpireOrdersService service = serviceWithBatchSize(200);

    // --- the happy path ---------------------------------------------------------------

    @Test
    void expiresAnOrderWhoseDeadlineHasPassed() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, PASSED));

        SweepResult result = service.sweep();

        assertEquals(new SweepResult(1, 1, 0, 0), result);
        assertEquals(
            OrderStatus.EXPIRED,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
    }

    /**
     * A deadline in the future is not a deadline that has passed, and the query must not round it
     * off. Nothing is examined at all -- the candidate list is empty, so no transaction is opened.
     */
    @Test
    void leavesAnOrderWhoseDeadlineHasNotArrived() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, NOT_YET));

        assertEquals(new SweepResult(0, 0, 0, 0), service.sweep());
        assertEquals(0, transactions.executions());
        assertEquals(
            OrderStatus.PENDING,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
    }

    /**
     * NO EXPIRY MEANS NEVER EXPIRES, and getting this wrong would be catastrophic rather than merely
     * wrong: {@code expiresAt} is optional and most orders in this codebase's own tests have none,
     * so treating null as "already past" would kill every open-ended order on the platform the first
     * time the sweeper ran.
     */
    @Test
    void neverExpiresAnOrderThatSetNoDeadline() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, null));

        assertEquals(new SweepResult(0, 0, 0, 0), service.sweep());
        assertEquals(
            OrderStatus.PENDING,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
    }

    /** A cancelled order is already finished. Expiring it would overwrite how it actually ended. */
    @Test
    void leavesAnOrderThatWasAlreadyCancelled() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(
            pendingOrder(merchantId, PASSED).cancel("changed my mind", NOW.minusSeconds(600))
        );

        assertEquals(new SweepResult(0, 0, 0, 0), service.sweep());
        assertEquals(
            OrderStatus.CANCELLED,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
    }

    // --- THE GUARD THIS ADR EXISTS TO PLACE --------------------------------------------

    /**
     * AN ORDER WITH A LIVE PAYMENT INTENT IS NOT EXPIRED, AND THIS IS THE MOST IMPORTANT TEST IN THE
     * CLASS.
     * <p>
     * Expiring it would leave that intent holding the slot of an order that can no longer be paid --
     * no second intent (the slot is taken), no confirm and no capture (both re-read payability), and
     * no route back through the API. That is exactly the shape of open item 1, arrived at from the
     * other direction, and ADR-014 exists to refuse it.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the {@code payments.hasLivePaymentIntent} check
     * from {@code ExpireOrdersService.expireOne}. The order then expires and the assertions on both
     * the status and the {@code held} count fail.
     */
    @Test
    void doesNotExpireAnOrderThatStillHasALivePaymentIntent() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, PASSED));
        payments.addLiveIntentFor(merchantId, order.orderId().value());

        SweepResult result = service.sweep();

        assertEquals(1, result.examined());
        assertEquals(0, result.expired());
        assertEquals(1, result.held());
        assertEquals(
            OrderStatus.PENDING,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
        assertEquals(0, history.changes().size());
        assertEquals(0, outbox.events().size());
    }

    /**
     * The held order's slot is released -- the intent failed or was cancelled -- and the NEXT sweep
     * expires it. The two rules compose: nothing is lost, only deferred, which is what makes the
     * guard a postponement rather than a leak.
     */
    @Test
    void expiresTheOrderOnceItsPaymentIntentIsNoLongerLive() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, PASSED));
        payments.addLiveIntentFor(merchantId, order.orderId().value());

        assertEquals(0, service.sweep().expired());

        StubPaymentActivity released = new StubPaymentActivity();

        assertEquals(1, serviceWith(released, 200).sweep().expired());
        assertEquals(
            OrderStatus.EXPIRED,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
    }

    /**
     * The guard is asked per candidate, under that candidate's own merchant. A sweeper that asked
     * once for the batch, or asked with the wrong merchant, would answer for the wrong order.
     */
    @Test
    void asksAboutEachCandidateUnderItsOwnMerchant() {
        MerchantId first = MerchantId.generate();
        MerchantId second = MerchantId.generate();
        Order held = repository.save(pendingOrder(first, PASSED));
        repository.save(pendingOrder(second, PASSED));
        payments.addLiveIntentFor(first, held.orderId().value());

        SweepResult result = service.sweep();

        assertEquals(2, payments.lookups());
        assertEquals(1, result.expired());
        assertEquals(1, result.held());
    }

    // --- idempotency ---------------------------------------------------------------------

    /**
     * RUNNING TWICE WRITES NOTHING TWICE. A second sweep -- or a second application instance on the
     * same schedule -- finds the order already EXPIRED and stops before any write.
     * <p>
     * The row counts are what this asserts, not the status: the status is right either way, and a
     * sweeper that appended a second timeline row and a second {@code order.expired} would still
     * leave an EXPIRED order behind. Double-announcing a transition that happened once is the bug.
     * <p>
     * <b>Sabotage that must turn this red:</b> remove the {@code order.hasExpiredBy(now)} re-check
     * from {@code expireOne}, or widen {@code InMemoryOrderRepository.findExpirable} to return
     * non-PENDING rows. Either makes the second run write again.
     */
    @Test
    void writesNothingASecondTimeWhenTheSweepRunsTwice() {
        MerchantId merchantId = MerchantId.generate();
        repository.save(pendingOrder(merchantId, PASSED));

        assertEquals(1, service.sweep().expired());
        SweepResult second = service.sweep();

        assertEquals(0, second.expired());
        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
        assertEquals(
            1,
            outbox.events().stream().filter(e -> e.eventType().equals("order.expired")).count()
        );
    }

    // --- batching and tenancy --------------------------------------------------------------

    /**
     * The batch is a bound on one sweep, not on the work. Three expirable orders and a batch of two
     * leaves the third for the next run, which is what stops a backlog loading the whole platform
     * into memory.
     */
    @Test
    void takesAtMostOneBatchPerSweep() {
        MerchantId merchantId = MerchantId.generate();
        repository.save(pendingOrder(merchantId, PASSED));
        repository.save(pendingOrder(merchantId, PASSED.minusSeconds(30)));
        repository.save(pendingOrder(merchantId, PASSED.minusSeconds(60)));

        ExpireOrdersService batched = serviceWithBatchSize(2);

        assertEquals(2, batched.sweep().expired());
        assertEquals(1, batched.sweep().expired());
        assertEquals(0, batched.sweep().expired());
    }

    /**
     * TENANT-AGNOSTIC IS THE POINT, AND TENANT-SAFE IS THE CONSTRAINT. The sweep must see both
     * merchants' orders -- a per-merchant sweeper would never run for a merchant nobody called an
     * endpoint for -- but every row it writes must carry that row's own merchant, never the other's.
     */
    @Test
    void sweepsAcrossMerchantsAndWritesEachRowUnderItsOwnMerchant() {
        MerchantId first = MerchantId.generate();
        MerchantId second = MerchantId.generate();
        Order firstOrder = repository.save(pendingOrder(first, PASSED));
        Order secondOrder = repository.save(pendingOrder(second, PASSED.minusSeconds(30)));

        assertEquals(2, service.sweep().expired());

        assertEquals(
            Map.of(
                firstOrder.orderId().value(), first,
                secondOrder.orderId().value(), second
            ),
            history.changes().stream().collect(java.util.stream.Collectors.toMap(
                change -> change.orderId().value(), OrderStateChange::merchantId
            ))
        );
        assertEquals(
            Map.of(
                firstOrder.orderId().value(), first,
                secondOrder.orderId().value(), second
            ),
            outbox.events().stream().collect(java.util.stream.Collectors.toMap(
                OutboxEvent::aggregateId, OutboxEvent::merchantId
            ))
        );
    }

    /**
     * A merchant with nothing expirable must come out of a sweep untouched, even when another
     * merchant's orders were swept in the same pass.
     */
    @Test
    void leavesAnotherMerchantsLiveOrdersAlone() {
        MerchantId sweeping = MerchantId.generate();
        MerchantId bystander = MerchantId.generate();
        repository.save(pendingOrder(sweeping, PASSED));
        Order untouched = repository.save(pendingOrder(bystander, NOT_YET));

        service.sweep();

        assertEquals(
            OrderStatus.PENDING,
            repository.findByOrderId(bystander, untouched.orderId()).orElseThrow().status()
        );
        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
    }

    // --- the timeline and the event ---------------------------------------------------------

    /**
     * SYSTEM, WITH NO ACTOR. There is no principal behind a timer, and naming the merchant here would
     * claim the merchant asked for this -- the opposite of what an automatic expiry is. It is the
     * first SYSTEM row this codebase writes anywhere.
     */
    @Test
    void writesOneSystemTimelineRowAndOneEventInsideTheTransaction() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, PASSED));

        service.sweep();

        assertEquals(1, transactions.executions());
        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
        assertTrue(history.appendedInsideATransaction());
        assertTrue(outbox.appendedInsideATransaction());

        OrderStateChange change = history.changes().get(0);

        assertEquals(order.orderId(), change.orderId());
        assertEquals(OrderStatus.PENDING, change.fromStatus());
        assertEquals(OrderStatus.EXPIRED, change.toStatus());
        assertEquals(OrderStateChange.ActorType.SYSTEM, change.actorType());
        assertNull(change.actorId());
        assertEquals(NOW, change.occurredAt());
    }

    @Test
    void carriesBothTheDeadlineAndTheSweepInstantInTheEventPayload() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, PASSED));

        service.sweep();

        OutboxEvent event = outbox.events().get(0);

        assertEquals("order.expired", event.eventType());
        assertEquals("ORDER", event.aggregateType());
        assertEquals(order.orderId().value(), event.aggregateId());
        assertEquals(merchantId, event.merchantId());
        assertEquals(1, event.eventVersion());

        Map<String, Object> payload = event.payload();

        assertEquals("PENDING", payload.get("previousStatus"));
        assertEquals("EXPIRED", payload.get("status"));
        // TWO DIFFERENT FACTS. The deadline is the merchant's; the sweep instant is the platform's,
        // and the gap between them is however long the interval is. A consumer reconciling against
        // the merchant's own records needs the first, not just the second.
        assertEquals(PASSED.toString(), payload.get("expiresAt"));
        assertEquals(NOW.toString(), payload.get("expiredAt"));
    }

    // --- resilience -----------------------------------------------------------------------

    /**
     * ONE BAD ROW MUST NOT STOP THE SWEEP. Candidates come back oldest deadline first, so an order
     * that throws every time would otherwise sit at the head of every batch and starve everything
     * behind it -- expiry silently disabled platform-wide by one row.
     */
    @Test
    void keepsSweepingAfterOneOrderFails() {
        MerchantId merchantId = MerchantId.generate();
        Order poisoned = repository.save(pendingOrder(merchantId, PASSED.minusSeconds(60)));
        Order healthy = repository.save(pendingOrder(merchantId, PASSED));

        // The port, implemented inline, because a fake that can be made to fail for exactly one
        // order is the only way to reach the catch clause without corrupting anything real.
        ExpireOrdersService withOneFailure = serviceWith(
            (askedFor, orderId) -> {
                if (orderId.equals(poisoned.orderId().value())) {
                    throw new IllegalStateException("payment module is down for this row");
                }

                return false;
            },
            200
        );

        SweepResult result = withOneFailure.sweep();

        assertEquals(2, result.examined());
        assertEquals(1, result.expired());
        assertEquals(1, result.failed());
        assertEquals(
            OrderStatus.EXPIRED,
            repository.findByOrderId(merchantId, healthy.orderId()).orElseThrow().status()
        );
        assertEquals(
            OrderStatus.PENDING,
            repository.findByOrderId(merchantId, poisoned.orderId()).orElseThrow().status()
        );
    }

    @Test
    void refusesABatchSizeBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithBatchSize(0));
    }

    /** An empty platform costs one query and opens no transaction. */
    @Test
    void doesNothingWhenThereIsNothingToSweep() {
        assertEquals(new SweepResult(0, 0, 0, 0), service.sweep());
        assertEquals(0, transactions.executions());
    }

    // --- helpers --------------------------------------------------------------------------

    private ExpireOrdersService serviceWithBatchSize(int batchSize) {
        return serviceWith(payments, batchSize);
    }

    private ExpireOrdersService serviceWith(PaymentActivityLookup lookup, int batchSize) {
        return new ExpireOrdersService(
            repository,
            history,
            getOrderService,
            lookup,
            outbox,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            batchSize
        );
    }

    private static Order pendingOrder(MerchantId merchantId, Instant expiresAt) {
        return Order.create(
            OrderId.generate(),
            merchantId,
            null,
            null,
            1999,
            "INR",
            null,
            Map.of(),
            expiresAt,
            CREATED_AT
        );
    }
}
