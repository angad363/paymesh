# ADR-003: Use opaque, prefixed public identifiers

## Status

Accepted

> Backfilled. This file was empty on `main` while CLAUDE.md and the code cited it
> as the authority for identifier format; the decision below is the one the code
> has implemented since the merchant capability.

## Context

Every capability exposes identifiers in URLs, responses, webhooks and logs.

A sequential database key leaks business volume: `/merchants/41` tells a caller
roughly how many merchants exist, and how fast that number grows. It also invites
enumeration, because the next id is always guessable. And an identifier that
carries no type is easy to pass to the wrong endpoint, where it either fails
confusingly or, worse, matches something real.

## Decision

Public identifiers are opaque strings of the form `<prefix>_<uuid>`.

- The prefix names the type: `mrc_` merchant, `usr_` user, `cus_` customer,
  `pmt_` payment-method token. Planned: `ord_`, `pi_`, `pay_`, `ref_`, `stl_`,
  `whe_`, `evt_`.
- The remainder is a random UUID, so ids are unguessable and carry no volume
  information.
- Each identifier is a value-object record that validates prefix and UUID in its
  compact constructor. `generate()` mints one; `from(String)` parses and
  validates. A malformed id fails at the boundary, not three layers in.
- Ids are assigned by the application, not the database. No sequential key is
  ever exposed.

Identifiers used as a tenant reference across capabilities live in
`com.paymesh.shared.tenant` rather than in the owning module, so a capability can
reference a tenant without depending on another module's domain (see ADR-001,
ADR-002).

## Consequences

Ids are self-describing in logs and support conversations, and a customer id sent
to a merchant endpoint is rejected as malformed rather than misinterpreted.

Costs: 40 characters per identifier instead of a few, and a `VARCHAR(40)` primary
key rather than a compact integer. Random primary keys also scatter B-tree inserts
rather than appending, which matters at high write volume — the mitigation, when
it matters, is a time-sortable scheme (UUIDv7) behind the same value object, with
no change to the format callers see.

Possessing an id never authorizes access. Unguessability is defence in depth, not
an authorization mechanism: every merchant-owned query is still scoped by tenant
(ADR-007).
