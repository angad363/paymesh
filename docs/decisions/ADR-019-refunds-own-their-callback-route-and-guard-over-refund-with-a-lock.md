# ADR-019: Refunds own their callback route, and over-refund is guarded by a lock and a trigger

- **Status:** Accepted
- **Date:** 2 August 2026
- **Supersedes:** nothing
- **Related:** [ADR-007](ADR-007-enforce-authentication-and-tenant-scoping.md) (tenancy),
  [ADR-008](ADR-008-cross-module-reads-through-a-consumer-owned-port.md) (consumer-owned ports),
  [ADR-012](ADR-012-deduplicate-and-order-provider-callbacks.md) (callback dedup + ordering),
  [ADR-016](ADR-016-in-process-event-dispatch-before-kafka.md) (event delivery),
  [ADR-018](ADR-018-post-the-ledger-from-events-with-the-invariants-in-the-database.md) (the ledger),
  SDD §16

---

## 1. Context

Refund is the last Phase 1 capability, and it was blocked on the Ledger rather than the other
way round: a refund without double-entry is an untraceable subtraction. The Ledger landed in
ADR-018 with a gap recorded in its own consequences — *"no reversal path exists… the
immutability triggers are what make the reversal the only available option when it arrives,
rather than the disciplined one."* This is it arriving.

Two things also came with it that were unreachable before. `PaymentIntentStatus` has declared
`PARTIALLY_REFUNDED` and `REFUNDED` since V8, and `project-status.md` verified by grep that
nothing could produce them. `payment_intents.refunded_amount_minor` has existed just as long and
never moved.

The money risk here is specific and it is not the same as Payment's. Payment's danger is
collecting twice; Refund's is **paying out more than was collected**, and it is reachable by
ordinary concurrency rather than anything exotic — two partial refunds submitted at the same
moment each read a total that excludes the other, both pass, and both insert. Neither request
did anything wrong.

## 2. Decision

Build SDD §16's core:

- `refunds`, `refund_state_history`, `refund_callbacks` (V16).
- `POST/GET /api/v1/refunds`, `GET /api/v1/refunds/{id}`, `POST /api/v1/refunds/{id}/cancel`.
- `POST /internal/v1/refund-callbacks/{provider}` — **Refund's own route** (§3).
- The over-refund rule enforced by **a row lock on the payment plus a deferred constraint
  trigger** — the lock for the concurrent case, the trigger for everything else (§4).
- `refund.succeeded` consumed by the Ledger (a reversal journal) and by Payment (its own
  status and refunded total).

## 3. Why Refund owns its callback route

The alternative was a `REFUNDED` member on Payment's `ProviderOutcome` and a refund id on its
existing callback. Fewer files, one route for everything a provider says.

It was rejected because Payment would then have to know refunds exist in order to route the
callback. That puts Refund's vocabulary inside Payment and points the arrow both ways in the
direction hardest to undo — and it silently couples two wire contracts, so adding a payment
outcome would change what a refund callback may say.

The cost is one refactor: `ProviderCallbackSignatureFilter` moved from
`payment.infrastructure.provider` to `shared.provider`, with its path prefix and its
payload-hash request attribute becoming constructor arguments so it names no capability.
`ModuleBoundaryTest.theSharedSignatureFilterNamesNoCapability` keeps it that way.

**The alternative to moving it was copying it** — two implementations of the one check standing
between a forged request and a merchant's money, where fixing one silently leaves the other
wrong. Two *instances* of one class is the right amount of duplication; two classes is not.

Each route keeps its own secret property (`paymesh.refund.callback-secret`) even though dev
supplies the same value to both. Two names is what makes them separable: rotating one after a
leak, or moving refunds to a different provider, becomes a config change rather than a code
change.

## 4. Why the over-refund guard is a trigger and not `refund_reservations`

SDD §16.4 specifies a `refund_reservations` table. It is not built.

A reservation row is a second record of a fact `refunds.status` already carries, and two records
of one fact can disagree — the same argument that kept `account_balances` out of V15. It also
would not fix the race on its own: a `UNIQUE` reference stops the same reservation twice, not two
*different* reservations that jointly overshoot.

### 4.1 The trigger alone does NOT settle the race, and this was measured

The first version of this ADR claimed a deferred constraint trigger was sufficient. **It is
not**, and the reason is worth stating precisely because it is easy to get wrong twice:

> A DEFERRED constraint trigger fires at COMMIT, but the query inside it runs on the snapshot of
> the **statement that queued it** — not a fresh one taken at commit time.

So when two transactions each insert a full refund, the second one's trigger looks at the database
as it was *before* the first committed, sees only its own row, and passes. Both commit. A
`RefundConcurrencyTest` written during review — two threads, one barrier, two full refunds of one
payment — let **both** through against a real PostgreSQL.

**What actually settles it is a row lock on the payment intent**, taken inside the create
transaction before head-room is read, through `PaymentLookup.findRefundableForUpdate`. Concurrent
refunds of one payment then take turns: the loser blocks until the winner commits, reads a total
that includes the winner's refund, and is refused by the ordinary pre-check with a readable
message. This is the `OrderLookup.findForUpdate` shape the codebase already uses.

Note what this means for the choice recorded in §4 above: the reservation table was rejected for
good reasons, but the *reason it was rejected in favour of* — the trigger — turned out not to be
the mechanism either. The mechanism is the lock; the trigger is the backstop.

### 4.2 The trigger stays anyway, as a backstop

It is still worth having, and it is still checked at COMMIT:

```sql
sum(amount_minor) WHERE status NOT IN ('FAILED','CANCELLED')
    <= payment_intents.captured_amount_minor
```

Three details are load-bearing:

- **Everything except FAILED and CANCELLED counts.** A PENDING or PROCESSING refund has moved no
  money *yet*, but the provider may be about to. Counting only SUCCEEDED would let a merchant
  queue ten full refunds while the first is in flight, each individually valid.
- **It fires on UPDATE as well as INSERT.** A status moving back out of FAILED re-arms an amount
  the check had discounted. Nothing does that today; the trigger does not depend on nothing doing
  it.
- **It compares against `captured_amount_minor`, never `amount_minor`.** On a partial capture the
  two differ by money that was never collected.

A **second, non-deferred** trigger refuses a refund whose currency differs from the payment's.
Without it, a 5000 JPY refund against a 5000 INR capture passes the amount check *exactly* —
integers carry no currency — and roughly sixty times the money that came in goes back out with
every constraint satisfied. Unreachable through the API, because `CreateRefundRequest` has no
currency field at all; present because "unreachable through the API" is not the standard this
codebase holds the money path to.

It catches what the lock cannot: a raw `INSERT`, a migration, or a future caller that does not
take this path at all. It is a guard against everything except the concurrent case, and the lock
is the guard for that one. Neither is redundant.

Both triggers were verified by deleting them: removing the over-refund trigger turns four tests
red and removing the currency trigger turns one red, with every Java-level test staying green in
both cases. The lock was verified the same way — removing it turns `RefundConcurrencyTest` red and
leaves every other test green.

## 5. Why Refund may import Payment when Order may not

`ModuleBoundaryTest` gives Order an **empty** allowlist against Payment, because Payment already
reads Order and a second arrow would make the pair cyclic. Refund is not in that position:
nothing in PayMesh imports Refund, so an adapter leaves the graph acyclic and Refund stays a
leaf. It therefore follows the `OrderLookup` shape (ADR-008) — the port and the adapter both
belong to the consumer, and exactly two files may name Payment: the adapter, and the
configuration that wires it.

The adapter returns `RefundablePayment`, Refund's own four-field record, with `refundable` as a
**boolean computed on Payment's side of the boundary**. Returning the status instead would put
Payment's enum in Refund's vocabulary and force Refund to be updated every time Payment gained a
state.

## 6. Why three modules move on one event

`refund.succeeded` is consumed twice, and neither consumer could have been a method call:

- The **Ledger** posts the reversal. Nothing may reach into the Ledger (ADR-018) — every posting
  traces to an event.
- **Payment** moves `refunded_amount_minor` and its own status. Refund may not write Payment's
  table any more than Payment writes Order's (ADR-016).

Payment is now on both sides of the bus — producing `payment.succeeded`, consuming
`refund.succeeded` — and still imports nothing new, because both directions are a `Map` read out
of an envelope. That is the property ADR-016 was built for, collected for the third time.

The reversal is keyed `refund-reversal:ref_…`, **not** on the payment: the payment's key is
already taken by its capture journal, and a payment may be refunded in parts. Keying on the
payment would collide on the first reversal and silently refuse every later partial refund —
money returned to a customer and never recorded, which is the worst failure this module has.

## 7. Consequences

**Good.**

- Money can go back, and it is recorded in three places that agree without any of them calling
  another.
- `PARTIALLY_REFUNDED` and `REFUNDED` are reachable. Every state in `PaymentIntentStatus` now is.
- The Ledger's correction mechanism is exercised rather than theoretical, which is what V15's
  immutability triggers were built to force.

**Bad, and known.**

- **The provider simulator cannot send refund callbacks yet.** Its `outbound_callbacks` table and
  `OutboundCallback` aggregate are modelled on payments — one target URL, payment outcomes — so
  refund callbacks are driven by a hand-signed HMAC request, exactly as payment callbacks were
  before the simulator existed. Closing this is a simulator change and its own migration.
- **`POST /internal/v1/refunds/{id}/retry` (SDD §16.3) is not built.** It is an ops route and
  there is no ops tooling; a failed refund is retried by creating another, which the released
  amount makes possible.
- **`refund_attempts` (SDD §16.4) is not built.** `payment_attempts` exists because a payment is
  confirmed, challenged and captured over several provider round trips. A refund is asked once
  and answered once, and `refund_callbacks` already records what arrived.
- **Cancel almost always answers 409.** Create writes PENDING and submits in one transaction, so
  a refund is PROCESSING by the time the caller sees it. That is honest rather than broken —
  PROCESSING means the provider may already have moved the money — but the endpoint's real use is
  clearing a refund that failed to submit.
- **Nothing reconciles a lost refund callback.** SDD §16.6's third line asks for it. A refund
  whose callback never arrives stays PROCESSING forever, holding its amount against the captured
  total. Payment has a `PROCESSING` timeout sweeper (ADR-015) and Refund has no equivalent; that
  is the most likely next PR.
- **`refund.created` has no consumer.** Emitted because the outbox write is what makes the fact
  recoverable — a consumer added later reads the backlog rather than starting blind.

## 8. Alternatives considered

**Refund writes `payment_intents.refunded_amount_minor` directly.** One update, no event, no lag.
Rejected for the same reason Payment does not write `orders.status`: it makes the two modules one
deployable and puts the money path back inside the module that does not own the row.

**A row lock on the payment intent instead of the trigger.** This was considered and rejected at
design time, on the grounds that a lock is an application-layer guard a direct INSERT bypasses.
That reasoning was right about the lock and wrong about the alternative: the trigger does not
cover the concurrent case at all (§4.1). Both are now in place, each covering what the other
cannot.

**SERIALIZABLE isolation for the create transaction.** Would also settle the race, by making the
loser fail with a serialization error. Rejected: it turns a readable 422 into a retryable 40001
the caller has to understand, and it puts an isolation-level requirement on a transaction that
other code may later join.

**One signature filter matching both callback paths.** Correct today, because both routes share a
secret. It would keep working silently on the day they stop, which is the failure mode worth
designing out.
