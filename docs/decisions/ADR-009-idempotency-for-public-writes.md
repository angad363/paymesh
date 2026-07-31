# ADR-009: Make public writes idempotent with a PostgreSQL record, deleted on 5xx

## Status

Accepted

## Context

The governing invariant of this project is that a request may fail or be retried,
but committed money movement must never be lost or silently duplicated. Retries
are not an edge case: a mobile client on a flaky connection, an HTTP library with
automatic retry, and an impatient merchant clicking twice all produce the same
request more than once, and the second one arrives after the first has already
succeeded but before its response was read.

Without a dedup layer, every public write is a duplicate waiting to happen. The
`merchant_order_ref` unique constraint catches one shape of this and is not a
substitute: it only helps when the merchant supplies a reference, and it cannot
give the caller back the *original* response.

SDD §23.1 specifies the scope — authenticated merchant + endpoint + idempotency
key — and leaves two questions that a future reader will otherwise ask.

## Decision

### 1. PostgreSQL is the authority. Redis is not, and may never be

The `idempotency_records` table (`V4`) is the single source of truth for whether a
key has been used. The primary key `(merchant_id, endpoint, idempotency_key)` is
not an access-path optimisation; it *is* the concurrency control. The filter
INSERTs an `IN_PROGRESS` row and **commits it before the handler runs**, so two
simultaneous retries collide on that key and the database picks exactly one
winner. Nothing reads before it writes.

Redis was the obvious alternative and is rejected as the authority:

- A cache eviction, a restart, a failover or a split brain would each re-open a
  key that had already been used. The cost of that is a duplicate financial write,
  which is the one outcome this project treats as unacceptable.
- The record has to be written in the same failure domain as the data it protects.
  An order committed in PostgreSQL and a key committed in Redis can disagree; a
  key in the same database can later be written in the same transaction as the
  effect it guards, which is where the outbox pattern will need it.
- The winner has to be decided atomically. `SETNX` can do this, but then two
  systems both believe they arbitrate writes, and the one with weaker durability
  wins ties.

Redis stays available as a *read* accelerator later (SDD §23.2), on the strict
condition that a cache miss falls through to PostgreSQL and a cache hit never
authorises a write on its own. This is the same rule as ADR-007's caches and the
same rule the SDD applies to reporting: non-authoritative or absent.

### 2. A 5xx deletes the record. A 4xx stores it

When the handler returns `>= 500`, or throws, the filter **deletes** the
`IN_PROGRESS` row and returns the error unchanged. When it returns anything below
500, the status and body are stored and replayed to any later retry.

The asymmetry is the point:

- A **500 means the server does not know what it did.** The write may have
  committed, may have partially committed, or may never have started. Recording
  "this key produced a 500" would pin the key to an answer nobody can justify and
  make a legitimate retry — the exact thing a caller is supposed to do after a
  500 — impossible. Deleting restores the caller to the state they were in before
  they tried.
- A **4xx is a decided answer.** The request was understood and rejected. Repeating
  it deserves the same rejection, cheaply, without a second trip through the
  handler. A validation failure that is replayed as a validation failure is
  correct.

The consequence is accepted openly: a retry after a 500 may duplicate an effect
that did in fact commit. That is a genuine risk, and it is smaller than the
alternative, which is a merchant permanently unable to complete a payment because
one transient failure burned their key. Narrowing it is the job of the
transactional outbox (roadmap item 7), which will let the effect and the record
commit together; it is not the job of this layer.

There is deliberately no `FAILED` status. A status that exists only to be ignored
is worse than no status.

### Consequences

- Public writes become idempotent by being listed in `IdempotentRoutes` and in no
  other way. A route that is absent passes through untouched, so this change is
  inert for every endpoint that already exists.
- The scope is stored as the **path template** (`POST /api/v1/orders/{orderId}/cancel`),
  not the concrete URI. A URI would scope the key per-resource, which is not the
  endpoint-shaped scope the SDD specifies.
- The filter runs after Spring Security, because the scope starts with the
  authenticated merchant. `POST /api/v1/merchants` is public and is therefore not
  covered; it has no tenant to key on. Its duplicate protection remains
  `uq_merchants_email`.
- Idempotency keys are compared, never trusted. Possessing one grants nothing,
  because the merchant comes from the verified token (ADR-007).
- Every response body of an idempotent route is now buffered in memory and stored
  in a `TEXT` column. Acceptable for JSON API responses; it would not be for file
  downloads, which is one more reason nothing is idempotent by default.
- Two dedup rules now exist and neither subsumes the other: `Idempotency-Key`
  answers "is this the same request?", `merchant_order_ref` answers "does this
  merchant already have an order for this purchase?". A retry with a *fresh* key
  still needs the second.

### Deliberately out of scope

- **Retention and reaping.** `created_at` is the column a sweeper will use; nothing
  schedules one. The table grows until it exists.
- **The "wait briefly then replay" variant** of the in-progress response. A `409
  REQUEST_IN_PROGRESS` is a correct answer and does not tie up a request thread.
- **Redis acceleration**, per above.
