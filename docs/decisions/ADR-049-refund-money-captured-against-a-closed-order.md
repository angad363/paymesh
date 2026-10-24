# ADR-049: Money captured against a closed order must be resolved, not dropped

- Status: Accepted (§5 decided: Option 1 — automatically issue a full refund)
- Date: 2026-10-24
- Scope: A money-correctness defect, not a service extraction. One behaviour of the Order consumer of
  `payment.succeeded` and one new consumer on the Payment/Refund side. The fix lands in both the
  monolith (`backend/`) and the extracted `payment/` deployable, which carry the same code. Touches
  ADR-010 (outbox in the state change's transaction), ADR-016 (the inbox), ADR-018 (the ledger posts
  from events), ADR-039/ModuleBoundaryTest (Order imports nothing from Payment), and invariant 1
  (committed money movement must never be lost, silently duplicated, or become unauditable).

## Context

`ApplyPaymentSucceededService.apply` locks the order, then refuses to move anything when the order is
not `PENDING`:

```java
if (order.status() != OrderStatus.PENDING) {
    log.info("Payment succeeded against an order that is already {} ...", order.status());
    return false;
}
```

That one guard answers two unrelated situations with the same silence:

1. **The order is already `PAID` or `PARTIALLY_PAID`.** A redelivery of `payment.succeeded`, or a
   second collection against an order that already moved. Dropping it is correct — this is the
   aggregate-level idempotency the comment describes, and `Order.markPaid` enforces it for every
   caller.

2. **The order is `CANCELLED` or `EXPIRED`.** The merchant cancelled, or the expiry sweeper closed the
   order, *while a payment intent against it was still PROCESSING*. The provider then captured — real
   customer money moved. The row lock makes cancel and apply take turns, so this is the ordering
   "cancel wins, then the capture lands": legitimate, reachable, and not rare under a slow provider.

The two consumers of `payment.succeeded` do not agree about case 2. Order drops it. **The ledger does
not** — `PaymentSucceededLedgerHandler` posts the merchant's available-balance credit from the event
regardless of order state (ADR-018: the ledger posts from the event, it does not consult the order).
So after a capture against a closed order the books say the merchant is owed the money, the customer
has been charged, and no order and no refund exist to explain either. The customer paid for nothing,
and nothing in the system is trying to give it back. That is a direct breach of invariant 1: the money
moved and became unauditable.

This was recorded as a known pre-existing defect in ADR-048 §"Known, pre-existing". This ADR resolves
it.

### Three facts that shape the fix

- **Order must not learn Payment or Refund types exist.** `ModuleBoundaryTest.orderNeverImportsPayment`
  has an *empty* allowlist. Order cannot call a refund service; it can only emit an event on its own
  outbox. `ApplyPaymentSucceededService` deliberately opens no transaction of its own — it rides inside
  the `EventDispatcher` transaction that already holds the `processed_events` row for the inbound event.
- **The inbound `payment.succeeded` payload already carries `paymentIntentId`** (written by
  `CapturePaymentIntentService` and `RecordProviderCallbackService`). Order's handler reads the payload
  as an untyped `Map` today; it can read `paymentIntentId` as one more opaque `String`, exactly as it
  already reads `orderId`, and carry it forward. No payment type crosses the boundary.
- **The refund path is built and idempotent.** `CreateRefundService` writes the refund PENDING and
  submits it in one transaction; `tr_refunds_within_captured` plus the `findRefundableForUpdate` row
  lock refuse an over-refund; `RefundAlreadyRequestedException` covers a duplicate request. A consumer
  can resolve the intent to refund with the existing `PaymentIntentRepository` (filter by `orderId`, or
  look the intent up directly by the `paymentIntentId` the event carries).

## Decision

### 1. Split the guard into two named outcomes

`ApplyPaymentSucceededService` stops treating "not PENDING" as one case.

- `PAID` / `PARTIALLY_PAID` → unchanged: log at INFO and return, the idempotent no-op.
- `CANCELLED` / `EXPIRED` → a distinct, **audited** outcome: write an order-state-history row recording
  that a payment was captured against a closed order (actor SYSTEM, reason naming the captured amount
  and the prior status), and append a new event to Order's outbox **in the dispatcher's transaction**.
  No new transaction, no refund call, nothing imported from Payment.

### 2. The new event: `order.payment_captured_after_close`

Aggregate `ORDER`, version 1, emitted only on the CANCELLED/EXPIRED branch. Payload:

| field | source |
|-------|--------|
| `orderId` | the order |
| `merchantId` | envelope merchant, copied to payload |
| `paymentIntentId` | echoed opaque from the inbound `payment.succeeded` payload |
| `capturedAmountMinor` | what the provider actually took |
| `currency` | the order's currency |
| `orderStatus` | `CANCELLED` or `EXPIRED` — which close happened |
| `occurredAt` | the capture instant the inbound event carried |

The name says what happened, not what to do about it — the same stance as `order.paid`. Whether the
consumer refunds or flags is §5's open question, and the event is correct under either answer.

### 3. The refund-side consumer

A new consumer **in the Payment/Refund side** (which *may* import Refund — `PaymentLookup`'s javadoc
records that Refund is the leaf of the module graph, so this stays acyclic) subscribes to
`order.payment_captured_after_close`. It:

1. Resolves the captured payment from `paymentIntentId` (the event carries it) through the existing
   `PaymentLookup.findRefundableForUpdate`.
2. Takes the action §5 decides — a full refund of `capturedAmountMinor` via the existing
   `CreateRefundService`, or an operator-review signal.

Consumer name is a stable inbox key (e.g. `refund.order-captured-after-close`), never the class name,
per the `processed_events` rule already stated in `PaymentSucceededHandler`.

### 4. Idempotency, stacked three deep

Redelivery of the new event, and a manual refund an operator may already have issued, are both safe:

- **The inbox** (`processed_events`, keyed by the consumer name + event id) drops a redelivered event
  before any work.
- **`RefundAlreadyRequestedException` / the request-id path** in `CreateRefundService` refuses a second
  automatic refund for the same cause.
- **`tr_refunds_within_captured` under the `findRefundableForUpdate` row lock** is the backstop that
  refuses any refund — automatic or manual — that would exceed the captured amount. If an operator
  already refunded by hand, the automatic refund finds no headroom and is refused with a readable
  message, not a double refund.

### 5. What the consumer does with the captured money — DECIDED: auto-refund in full

**Option 1 is chosen.** The refund-side consumer issues a full refund of `capturedAmountMinor` through
the existing `CreateRefundService`. The customer is made whole within one event hop, no money sits
unexplained, and the books self-heal: the refund's own `refund.succeeded` posting reverses the ledger
credit through a new journal (ADR-018), rather than anyone editing the credit away. This is the
strongest position against invariant 1 — the defect this ADR exists to close is money left captured
and unaccounted-for, and auto-refund leaves none.

The rejected alternative (**Option 2 — raise an operator-review signal and leave the capture in
place**) preserves a merchant's choice to fulfil the cancelled order and keep the payment, but it keeps
the customer's money captured until a human acts and lets a missed alert leave it sitting indefinitely
— re-opening the very hole this ADR closes. A middle path (auto-refund by default with a per-merchant
"hold for review" setting) is more than this defect needs today and is recorded only so it is not
mistaken for unconsidered; it can be layered on later without changing the event or the consumer's
inbox key.

## Consequences

- Case 2 stops being a silent money leak: every capture against a closed order leaves an order-state
  history row, an event, and (under Option 1) a refund — all three auditable.
- One new event type and one new consumer; no schema change, no new synchronous dependency. The ledger
  handler is untouched — its credit is correct and, under Option 1, is reversed by the refund's own
  `refund.succeeded` posting, so the books return to zero through a new journal rather than an edit
  (ADR-018).
- The same fix must be applied to both `backend/` and `payment/`. Until Option 1 ships, the money leak
  remains, so §5 should be decided before, not after, PR 14.
- `ApplyPaymentSucceededService` now returns more than a boolean's worth of meaning; its contract and
  the `order.payment_captured_after_close` emission want a test that fails if the CANCELLED/EXPIRED
  branch ever silently returns to dropping the event.

## Review amendments

The code review of this change surfaced one real defect in the first implementation and two findings
that were considered and not acted on.

- **The refund consumer must GUARD, never CATCH, the reference collision (fixed).** The first cut
  caught `RefundAlreadyRequestedException` around `CreateRefundService.create` and returned. That does
  not work: `create` joins the dispatcher's transaction (the shared `TransactionTemplate` is
  `PROPAGATION_REQUIRED`), and the `uq_refunds_merchant_reference` collision surfaces from
  `saveAndFlush` as a `DataIntegrityViolationException` that marks the shared transaction
  rollback-only *before* the catch runs. The outer commit then throws `UnexpectedRollbackException`,
  the `processed_events` claim rolls back, and the relay redelivers forever — a poison message. The
  fix reads `RefundRepository.existsByMerchantReference` under the payment row lock and skips before
  `create` is ever called, so every condition that would make `create` throw (not refundable, no
  head-room, reference taken) is now a read-then-return. `create` is called only when it is certain to
  succeed. Regression test: `aFailedAutoRefundIsNotRetriedIntoAReferenceCollision` (a FAILED first
  attempt frees head-room but keeps the reference) — it throws out of `dispatch` if the guard regresses.
- **The PAID / PARTIALLY_PAID branch is deliberately NOT reconciled (considered, rejected).** A review
  finding proposed refunding *any* capture that does not move the order, including against an
  already-PAID order. That is unsafe here: `ApplyPaymentSucceededService` cannot distinguish a
  redelivery of the same payment (the legitimate idempotent no-op) from a genuinely distinct second
  collection, so reconciling the PAID branch would refund legitimately-paid orders on an
  at-least-once duplicate with a fresh event id. Whether a second *distinct* successful capture against
  a PAID order is even reachable (intent creation is gated by `existsLiveForOrder`, and synchronous
  `capture` is gated by `requireStillPayable`) is a separate question; it is left as a recorded
  pre-existing concern, not folded into this fix.
- **The three pre-checks are load-bearing, not duplication.** `not refundable`, `reference already
  taken`, and `no head-room` each cover a distinct cause, and each exists specifically so `create`
  cannot throw into the shared transaction. They are not a redundant copy of `CreateRefundService`'s
  own head-room rule.
