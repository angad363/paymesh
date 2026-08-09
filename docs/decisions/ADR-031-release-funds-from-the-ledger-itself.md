# ADR-031: A merchant balance becomes settleable, and the ledger is its own record of that

**Status:** Accepted
**Date:** 2026-08-09
**Implements:** SDD §15.1 and §17.4, partially and deliberately. Prerequisite for Settlement (PR 4).

## Context

`MERCHANT_AVAILABLE` was named in `AccountType`'s javadoc as deliberately absent, because it "needs
a settlement schedule to move money out of pending". `MerchantBalance` omitted `availableMinor`
rather than returning zero, and said why: "adding it later is a backwards-compatible change;
reversing the meaning of a field that has been reading zero is not."

Both were right, and this ADR is those predictions coming due. Settlement (PR 4) cannot start until
something makes money settleable.

## Decision

**A scheduled job posts a balanced transaction per payment once its funds clear the merchant's
holding period** — debiting `MERCHANT_PENDING`, crediting `MERCHANT_AVAILABLE`.

Both are the same merchant's liability, so a release nets to zero against PayMesh's own position.
Nothing is created; a claim stops being conditional. That is why it is a journal rather than a
status flag: the two liabilities are separate accounts, so the move between them is visible, dated,
and reversible like anything else in the ledger.

### The job carries no state, and that is the main call

The plan reserved a migration for "release job state". It was not built. Two facts the ledger
already holds answer everything the job asks:

| Question | Answered by |
|---|---|
| Has this payment been released? | `uq_ledger_transactions_idempotency` on `funds-released:pi_x` |
| How much is left to release? | The signed sum of pending-account lines across every journal referencing that payment |

A state table would be a second copy of both, and the failure mode of a second copy is that it
disagrees with the first — the argument `BalanceRepository` already makes for summing entries
rather than projecting them (ADR-018 §5).

The second row of that table has a property worth naming: **a released payment sums to zero,
because its own release journal is part of the sum.** The job is therefore idempotent in arithmetic
as well as by unique key. Three consecutive runs post one journal, and the test says so.

### Refund reversals now reference the payment, not the refund

This is the one change to an existing journal's shape, and it is what makes the sum above work.

A release must move the payment's **net** position. Release the gross after a partial refund and the
totals stay right while the split goes wrong: capture 10000, refund 3000, release 10000 leaves
pending at −3000 and available at 10000. Available is the figure Settlement pays against, so the
merchant gets paid 10000 for a payment worth 7000 to them.

The obvious way to get the net is to ask Payment, which tracks `refunded_amount_minor`
authoritatively. **`ModuleBoundaryTest` seals that arrow in both directions with empty allowlists,
and seals `shared` too** (ADR-018 §6). There is no version of that which does not delete a
deliberate rule.

It turned out not to be needed. The refund event already carries `paymentIntentId`, the Ledger's
handler already read it, and `PostRefundReversalService` already took it as a parameter — and used
it for nothing but a log line. Pointing the reversal's reference at the payment makes the Ledger
self-sufficient. The refund id is not lost: it stays in the idempotency key
`refund-reversal:ref_x`, which is where "which refund caused this journal?" is answered.

**Rows written before V29 are not backfilled.** `tr_ledger_transactions_immutable` refuses UPDATE,
and its own comment names re-pointing a posted journal at a different reference as exactly the thing
it refuses. So a refund posted before V29 is not subtracted by the per-payment sum and such a
payment can over-release. In a real deployment that is a one-off reconciliation at migration time;
here it is nine dev rows. Rewriting them would be the history edit this ledger exists to prevent.

### A refund after release comes out of available

Once released, nothing of that payment is pending. Debiting pending would drive it negative while
leaving available still claiming money the merchant no longer has — and available is what gets paid
out. The discriminator is the release journal itself, since releasing moves *all* of a payment's
remaining pending, so "released" and "nothing of it is pending" are the same fact.

**`availableMinor` may go negative.** A merchant refunding after being paid out owes PayMesh the
difference, and a payment platform has to be able to say so. Clamping at zero would be a second copy
of the truth that disagrees with the entries.

### One column of config, not SDD §17.4's five

`settlement_configs` carries the holding period and nothing else. The schedule, minimum payout,
currency and payout account are Settlement's, and none has a reader here: a schedule nothing runs
to, a minimum nothing compares against, an account nothing pays into. A column whose semantics are
decided by a capability that does not exist is a column that gets them wrong. PR 4 adds them as an
ordinary `ALTER` against a table whose meaning has settled.

Seconds as an integer, not a PostgreSQL `INTERVAL`: an `INTERVAL` can express "1 month", and a
holding period whose length depends on which month it is asked in cannot be reasoned about by a
merchant or reproduced by a test.

**The platform default is a value, never a row.** Writing one on a merchant's behalf would make
"never configured" indistinguishable from "configured to exactly the default", so a later change to
the default would silently skip everyone who had been defaulted into a row — the opposite of what
changing a default means.

## Consequences

### The integration test caught a constraint the whole stack agreed about and the database did not

V29 taught `ck_ledger_accounts_owner` about the new account type and stopped there. There are
**two** constraints naming account types — owner says which carry a merchant, `ck_ledger_accounts_type`
says which exist at all. The enum, the migration, the domain and every unit test agreed; the first
real release failed on the second CHECK. Only a test that actually opened the account could find it.

A test of ours was also wrong while the code was right: the default-period test asserted funds were
held, using a capture constant older than the seven-day default, so they had legitimately cleared.
It captures at the real `now` instead, because that test is about the default's *length*.

### One new boundary, allowlisted to one file

The Ledger asks Settlement for a holding period through `HoldingPeriodPolicy`, implemented by a
single adapter, with a boundary test naming that file and refusing any other.

That arrow points **out** of the Ledger, which is a different thing from the one ADR-018 §6 seals.
Nothing here lets an outside caller move money: the release job still posts its own journal, from
inside the Ledger, in response to time passing. What crosses is a duration.

### Known limits

- The candidate query is an anti-join, so already-released captures are filtered but still visited
  as the table grows. A partial index on unreleased captures is the upgrade, and it is a measurement
  rather than a guess.
- `reserved` and `inSettlement` are still omitted from the balance for the original reason: nothing
  produces them.
- The release job releases per payment, so a merchant with a very large backlog drains at
  `batch-size` per interval rather than all at once. That is deliberate — each release is its own
  transaction, and one bad payment costs one payment.
