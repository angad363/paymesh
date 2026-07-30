# ADR-006: Defer customer PII encryption, but ship the schema shape it needs

## Status

Accepted

## Context

SDD section 10 requires the Customer Service to "store encrypted PII and hashed
lookup fields", and section 10.6 makes the split explicit: search matches on
deterministic hashes, display reads the encrypted value.

Encryption at rest is not a library choice, it is a key management problem. This
project has no key store, no key rotation, no separation between the credential
that reaches the database and the credential that decrypts its contents, and no
environment where secrets live anywhere but a committed `application.yaml`. A
symmetric key held next to the ciphertext protects nothing: an attacker who can
read the table can read the key. Building that today would produce something
that *looks* encrypted in review and in the SDD checklist while defending
against no real attacker — worse than plaintext, because plaintext at least
advertises its own risk.

The cost of waiting is a migration later. The shape of that migration is the
thing worth deciding now: if customer lookups query the email column directly,
encrypting it means rewriting every query, every index and every access path.

## Decision

Customer PII is stored in plaintext for now. The schema is nonetheless the
encrypted-design schema.

`V3__create_customers.sql` splits each PII field in two:

- a **display** column (`email`, `phone`) that is read but never queried, and
- a **lookup** column (`email_hash`, `phone_hash`) holding a deterministic
  SHA-256 of the normalized value, which is what the composite indexes cover.

`Customer` derives each hash from the normalized value in `create(...)`, so the
pairing between normalization and hashing cannot drift apart across layers.
`reconstitute(...)` restores both verbatim rather than recomputing.

Introducing encryption therefore changes what the display columns *contain*. It
does not change the tables, the indexes, the repository methods, or any query.

The lookup hash is currently unsalted SHA-256. It is a search key, not a secret,
and it protects nothing an attacker does not already have from the plaintext
column beside it.

## Consequences

**Risk accepted:** anyone with read access to the `customers` table — a database
backup, a snapshot, a leaked connection string, an over-broad support query —
reads every customer's email, name and phone in the clear. Unsalted hashes of
low-entropy values (emails, phone numbers) are additionally reversible by
dictionary. This is acceptable only because PayMesh handles no real people:
it is an educational system with simulated merchants and simulated buyers.

**Before this capability touches any real person's data, all of the following
must land:**

1. A key management service (KMS, Vault, or cloud equivalent) holding a data
   encryption key that the application never persists and no operator can read
   from the database.
2. Envelope encryption on the display columns, with key rotation and re-wrap
   supported without downtime.
3. The lookup hashes changed from bare SHA-256 to HMAC-SHA256 under a pepper
   held in the same key store, with existing rows rehashed in the same
   migration.
4. Access to the plaintext scoped and audited — decryption is a privileged
   operation with a log entry, not an ambient capability of the service.
5. A retention and erasure path, since encrypted PII is still PII.

Until then, the deferral is recorded in code at
`com.paymesh.customer.domain.LookupHash` and in the header of
`V3__create_customers.sql`, so it is visible at both the point of use and the
point of storage rather than only here.

## Notes

`customer_status_history` (SDD 10.4) is also deferred. It is an audit table for
a lifecycle transition that no use case performs yet — `CustomerStatus.BLOCKED`
is currently only reachable by `reconstitute`, since the risk capability that
would drive the transition does not exist. It arrives with the consumer for
`risk.customer.blocked`.
