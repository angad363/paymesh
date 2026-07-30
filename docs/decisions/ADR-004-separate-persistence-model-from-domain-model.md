# ADR-004: Keep the JPA persistence model separate from the domain model

## Status

Accepted

## Context

The merchant capability moved from an in-memory repository to PostgreSQL. JPA
needs a no-argument constructor, mutable fields, and an identifier type it can
manage. The domain aggregate needs the opposite: a private constructor, no
setters, normalization and invariant checks in a static factory, and identifiers
as value objects.

Annotating `Merchant` with `@Entity` would satisfy JPA by weakening every one of
those guarantees, and would point the dependency arrow outward from `domain` to
`jakarta.persistence`. Domain tests would then need a persistence context to run.

## Decision

Each persisted aggregate has two types.

- The domain aggregate stays framework-free and owns its invariants.
- A `<Aggregate>JpaEntity` in `infrastructure/persistence/jpa` describes the row
  and owns nothing else: no validation, no normalization, no factories.
- A hand-written `<Aggregate>JpaMapper` translates in both directions. No
  MapStruct and no reflection, so a schema change surfaces as a compile error.
- The application layer keeps its own repository interface. A
  `Jpa<Aggregate>Repository` adapter implements it and is the only class aware of
  Spring Data.

Identifiers and enums cross into the entity as `String`, so the persistence
layer never depends on domain types.

Two consequences follow.

Reading a row back requires restoring state that the registration factory cannot
express, such as a non-initial status or an `updatedAt` later than `createdAt`.
Aggregates therefore expose a `reconstitute(...)` factory alongside their
intent-revealing factories. It deliberately skips normalization: those values
passed through the domain before they were stored, so re-normalizing on read
would mask corruption rather than repair it.

Database constraint violations are translated by the adapter into the existing
application exceptions. `uq_merchants_email` becoming
`MerchantEmailAlreadyExistsException` is what makes the registration race safe:
the service's `existsByEmail` check is a check, not a lock, and the loser of a
race must still receive a 409 rather than a 500.

## Consequences

Accepted cost: two types and a mapper per aggregate, updated together whenever
either side changes.

Gained: the domain stays plain Java and testable without Spring; schema changes
do not ripple into business rules; Hibernate's `ddl-auto=validate` compares the
entity against the Flyway-owned schema at every startup and fails fast on drift.

## Notes

Hibernate maps `String` to `VARCHAR`, so columns declared `CHAR(n)` in a
migration require `@JdbcTypeCode(SqlTypes.CHAR)` on the field or schema
validation fails at startup.

Writes currently use `saveAndFlush`, which issues a `SELECT` before the `INSERT`
because identifiers are application-assigned and entities carry no `@Version`.
This is one extra query per write and is marked in the code with its upgrade
path (`Persistable#isNew` or `EntityManager.persist`).
