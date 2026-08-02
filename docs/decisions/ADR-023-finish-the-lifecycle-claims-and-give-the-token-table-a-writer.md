# ADR-023: Finish the lifecycle claims, and give the token table a writer

- **Status:** Accepted
- **Date:** 3 August 2026
- **Amends:** [ADR-021](ADR-021-make-the-lifecycle-states-reachable-and-enforce-them.md) §6 —
  a consequence it recorded was not true when it was written
- **Related:** [ADR-015](ADR-015-time-out-processing-payments-to-failed.md) (payment timeout),
  [ADR-019](ADR-019-refunds-own-their-callback-route-and-guard-over-refund-with-a-lock.md) (refunds),
  SDD §10.3, §16.6

---

## 1. ADR-021 claimed something that was not true

Its consequences said:

> A merchant can be stopped. **A user can be disabled. A customer can be blocked.** All three are on
> the record with an actor and, where it matters, a required reason.

Only the first was true. `Customer.block`, `Customer.unblock`, `User.suspend`, `User.reactivate` and
`User.close` all existed as aggregate methods **that nothing called** — no service, no endpoint. The
states stayed exactly as unreachable as before.

That is the same defect ADR-021 was written to fix, one layer up: a capability the domain can
express and the system cannot produce. It is worth naming plainly rather than quietly closing,
because the ADR was written *while* building the fix and the claim was believed at the time. An
audit that only reads the domain would have agreed.

This change makes the customer half true and **records the user half as still outstanding** (§5).

## 2. `payment_method_tokens` had no writer, and never had

The table has existed since **V3**. It was tenant-foreign-key-fixed in **V6**. Nothing has ever
inserted a row.

SDD §10.3's `POST/DELETE /v1/customers/{id}/payment-methods` were never built, so *"attach a payment
method"* on a payment intent attached a payment method **type** — the string `CARD` — and no card
was ever on file for any customer. A bug was fixed in a table nobody could put a row in.

It now has attach, list and detach.

**It stores a reference, never a card.** The request has no field that could carry a PAN, which is
the only reliable way to guarantee one is never stored. The response has no field for the
`providerToken` either: that is the one value in the row that could charge the card, the caller
already supplied it, and echoing it would turn every list into a way to harvest chargeable handles.

**Detach is a timestamp.** Same rule as `api_credentials.revoked_at` — a deleted token cannot answer
"was this card on file when that payment was taken".

**The row and its event commit together**, which is ADR-010 and was missing in the first draft of
this change — found in review. Without the transaction the two are separate auto-commits, so a
crash between them leaves either a card on file that nothing was told about, or an event announcing
a card that does not exist. Every other producer in the codebase already did this; this one did not.

**Attach is idempotency-registered**, also found in review. It is a create under a unique
constraint, so a retried attach whose first attempt committed would collide and answer 409 — the
wrong answer to a network retry of a request that worked, which is the reasoning capture is on that
list for.

### The two unique constraints mean different things

`uq_payment_method_tokens_provider_token` (V3) is **not** partial: a provider token is unique per
merchant and provider forever, detached or not. The V19 fingerprint index **is** partial: the same
*card* may be re-attached after removal.

So re-attaching a card the customer removed needs a **fresh provider token**, which is what would
happen in reality. The adapter reports the two collisions differently, because the fix differs —
telling a caller "this card is already attached" when the real problem is a re-used handle sends
them looking at a card that is not there.

## 3. A refund the provider never answered held head-room hostage forever

ADR-019 recorded this as the known gap. It is not an untidy row:

A `PROCESSING` refund **counts against the captured amount**. So one lost callback permanently
reduced what the merchant could refund on that payment — a 4000 refund that vanished blocked 4000
of head-room for good, and nothing reported it. The merchant discovered it when a legitimate refund
was rejected as an over-refund.

Payment has had a sweeper for exactly this shape since ADR-015. Refund shipped without one.

**Failing is the safe direction and it is still a guess.** The provider may have moved the money and
lost the callback, in which case timing out to `FAILED` means PayMesh believes no refund happened
when one did — showing up as a refundable balance that is too high and a second attempt that
double-refunds.

The alternative is worse in the ordinary case: leaving it `PROCESSING` forever holds head-room
hostage on *every* lost callback, including the overwhelming majority where the provider genuinely
never acted. So it fails them on a deliberately long timer — **six hours, longer than Payment's
one** — and the real answer is reconciliation against the provider's own record, which does not
exist. **This makes the gap tolerable; it does not close it.**

Two details carried over from the sweepers that already exist:

- **Each row in its own transaction, and `errored` counted rather than thrown.** Open item 2 records
  that one unmappable row permanently and silently disables the other two sweeps. Repeating that
  here would be repeating a known bug on purpose.
- **Re-read under a lock before failing.** A callback may have settled the refund between the query
  and the write, and failing a refund the provider just succeeded is the worst outcome this class
  has.

A callback arriving after a timeout finds the refund `FAILED`, so it is recorded and not applied —
on the record for whoever reconciles later, which is what should happen.

## 4. Consequences

**Good.**

- `CustomerStatus.BLOCKED` is reachable, audited, and enforced: a blocked customer cannot have a
  card attached.
- `payment_method_tokens` holds rows for the first time since V3.
- A lost refund callback costs a merchant six hours of head-room rather than all of it forever.

**Bad, and known.**

- **The refund timeout can be wrong**, in the direction of believing money did not go back when it
  did. Named above; reconciliation is the only real answer.
- **Blocking a customer does not stop payments already in flight**, deliberately: a customer who has
  been charged is owed an outcome whatever the merchant now thinks of them, and refunds of existing
  payments must stay possible for the same reason.
- **`PATCH /customers` does not touch `merchantReference`**, which is the merchant's own key and
  very likely their join key. Changing it is creating a different customer.

## 5. What is still outstanding, stated plainly

- **`UserStatus.SUSPENDED` and `CLOSED` remain unreachable.** The aggregate methods exist and
  nothing calls them, exactly as this ADR criticises in §1. They are not fixed here because there is
  no admin user API at all, and *who* may disable a user is a real question — platform staff, or a
  merchant admin over their own staff? — that deserves an answer rather than a guess. **A departed
  employee's account still cannot be disabled**, and access tokens remain unrevocable for their
  15-minute lifetime (open item 11).
- **`GET /v1/customers` (SDD §10.3's search) is not built.** A merchant can create a customer and
  fetch one by id, and cannot list them.

Both are recorded in `docs/project-status.md` rather than left for the next audit to rediscover.
