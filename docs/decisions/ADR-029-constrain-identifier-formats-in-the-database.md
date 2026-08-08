# ADR-029: Constrain identifier formats in the database, and accept that it does not make mapping safe

**Status:** Accepted
**Date:** 2026-08-06
**Supersedes:** nothing. Extends ADR-003 (opaque prefixed identifiers).

## Context

ADR-003 says every public identifier is `<prefix>_<uuid>`, and every `XxxId` value object enforces
it: `MerchantId.from` refuses anything that is not `mrc_` followed by a canonical UUID, and throws
when it sees one.

The database never agreed. Every one of those columns was a bare `VARCHAR` carrying a primary key
or a foreign key and nothing else, so PostgreSQL would store `not-a-merchant-id` happily — an
identifier the application cannot read back.

This was not theoretical, and we found it the expensive way. Open item 2 described five scheduled
sweeps that mapped candidate rows outside their per-item `try/catch`, and it was filed as *latent*:
"needs database state the current CHECKs forbid". That premise was wrong. Nobody had checked
whether the identifiers were constrained, and none of them were. A single malformed id would have
disabled order expiry, payment timeout, refund timeout, abandoned-checkout cleanup and the
simulator's dispatcher — permanently and silently, because the bad row sorts first in every
subsequent batch.

CLAUDE.md already states the governing preference: prefer a database constraint over an application
check. The application check turns a violation into a readable 409/422; the constraint is what makes
it true.

## Decision

**Every identifier column whose contents PayMesh mints gets a `CHECK` enforcing its ADR-003 format**
— 63 constraints across 20 identifier types, in migration V26.

The predicate lives in one `IMMUTABLE` function rather than 63 inline regexes:

```sql
CREATE FUNCTION is_prefixed_id(value text, prefix text) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT value ~ ('^' || prefix ||
        '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
$$;
```

Only the prefix varies between these columns, so inlining the pattern would mean 63 chances to
fat-finger a character class in a way nothing would catch: a subtly wrong regex still accepts every
id the application mints and only misbehaves on the malformed row the constraint exists to reject.
`IMMUTABLE` is required — PostgreSQL refuses a `CHECK` whose expression is not. `STRICT` means NULL
in, NULL out, which a `CHECK` treats as satisfied; that is correct, because several of these columns
are legitimately nullable and an absent identifier is not a malformed one.

The pattern is lowercase-hex only and pins no version nibble. Not pinning the version is deliberate:
`XxxId.from` does not check it either, and `4` would reject a v7 id the application would accept.

**The lowercase half did not match Java when this was written, and Java was fixed to match it.**
Fifteen of the eighteen id types round-tripped the parsed UUID with `equalsIgnoreCase`, so
`mrc_550E8400-…` and `mrc_550e8400-…` were both legal identifiers. The other three —
`ApiCredentialId`, `KycSubmissionId`, `PaymentMethodTokenId` — called `UUID.fromString` and threw
the result away, which is a parse rather than a validation: it also admitted padded shorthand like
`apc_1-1-1-1-1`, which the parser silently expands to a real UUID.

So the constraint was *stricter* than the type it was supposed to mirror. **The constraint is the
correct half.** These columns are primary keys, and two accepted spellings of one UUID is two rows
for one thing, with nothing to stop it. All eighteen now round-trip with `equals`.

This is worth naming as a pattern: writing the constraint is what surfaced that the invariant the
domain type advertised was not the invariant it enforced. Nothing else would have — every id the
application mints is canonical, so no test and no amount of production traffic would ever have
produced the divergent case.

### What is deliberately not constrained

Five categories, and the migration names each one so the next reader does not have to work out
whether they were missed or excluded:

| Column | Why not |
|---|---|
| `outbox_events.aggregate_id` | Polymorphic by `aggregate_type`: `ord_`, `pi_`, `ref_` and `cus_`. A CHECK could name the union, but it would need editing every time a new aggregate emits an event — a constraint that silently rots. |
| `ledger_transactions.reference_id` | Polymorphic by `reference_type`, which `LedgerTransaction` defines as exactly `PAYMENT_INTENT` and `REFUND`, so it holds `pi_` or `ref_` and never `ord_`. Same reasoning. |
| `*_state_history.actor_id`, `*_status_history.actor_id` | Not an identifier. "A merchant id, a provider name", whichever is knowable, and NULL for a SYSTEM actor. |
| `refresh_tokens.family_id`, `security_events.event_id` | Bare UUIDs, internal only, never exposed. V2 says ADR-003's prefix rule does not apply. |
| `external_event_id`, `provider_reference`, `provider_token` | The provider's identifiers. We neither mint them nor get to say what they look like. |
| `idempotency_key`, `kyc_submissions.registration_id` | Merchant-supplied free text, never interpreted. |

## Consequences

### This does not make row mapping safe, and believing otherwise is the trap

**The per-item `try/catch` from open item 2 stays load-bearing.** Constraining identifiers narrows
what can be unreadable; it does not close the set. `orders.metadata` and `payment_intents.metadata`
are JSONB with no shape constraint and map to a `Map`, so a JSON *array* in either is still a row no
mapper can rehydrate.

We know this because the constraints closed the door our own regression tests came through — those
tests planted a malformed `merchants.merchant_id` — and rewriting them around the metadata door
worked immediately, with the identical blast radius (reverting the fix takes down 12 of 14 tests in
`OrderExpiryIntegrationTest`).

**We deliberately did not add `CHECK (jsonb_typeof(payload) = 'object')` to close that door too.**
Not because it would be wrong, but because chasing every unmappable shape with a constraint is the
wrong end of the problem. The boundary is what makes the system survive the row we did not predict.
A constraint we did predict is defence in depth behind it, never a replacement for it.

### It found a sixth instance of the bug it was cleaning up after

Removing the malformed-id door from `EventDeliveryIntegrationTest` left exactly one other way to
poison an outbox row — the payload — and the relay did not survive it. `findUnpublished` returned
entities, so Hibernate deserialized the JSONB payload while materializing each one, *inside the
repository call and outside the per-item try*. `UnpublishedEvent` was scrupulous about keeping
identifiers raw and never noticed the payload arriving already parsed; its javadoc claimed
`toEvent()` was "the only place this row is allowed to throw", and for the payload that was false.

Fixed here in the same shape as the other five: the query selects `payload::text`, the record carries
the raw string, and `toEvent(ObjectMapper)` parses inside the caller's try.

This is the second time this month that a claim of exhaustiveness has been wrong. The pattern worth
naming: **"I fixed every instance" is a claim about a search, and a search nobody re-ran is a claim
nobody checked.** Both times the missing instances were found by removing an assumption, not by
adding a test.

### Operational

- Applied against a populated V25 database with zero violating rows, so no data repair step. If it
  ever fails on another database the failure is the point: it found an identifier nobody could have
  read back anyway.
- Constraint names are `ck_<table>_<column>_format`, all within PostgreSQL's 63-character limit.
- A future identifier type must add its own constraint. There is no mechanism forcing that, which is
  a known gap; the alternative considered was `CREATE DOMAIN` per id type, rejected because
  `ddl-auto=validate` behaviour against domain-typed columns is unproven here and a failure mode
  would be "the application will not start".
