-- Adds POSTED to the batch lifecycle: PENDING_PAYOUT -> POSTED (the Ledger journalled the cut,
-- ledger.batch_posted) -> PAID | RETURNED. A payout is submittable only once its batch is POSTED
-- (ADR-047 section 8).
--
-- BACKFILL: only a batch whose cut journal the Ledger ALREADY posted is marked POSTED (it predates
-- the gate, so no ledger.batch_posted will ever arrive for it). A batch whose batch_cut is still in
-- flight stays PENDING_PAYOUT and reaches POSTED only through ledger.batch_posted, so a cut the
-- Ledger later rejects can never have had its payout submitted. The journal is found by its
-- idempotency key, 'settlement-batch-cut:' || settlement_batch_id (LedgerTransaction).
-- The status is the one column the append-only trigger permits to change.
ALTER TABLE settlement.settlement_batches DROP CONSTRAINT IF EXISTS ck_settlement_batches_status;
ALTER TABLE settlement.settlement_batches ADD CONSTRAINT ck_settlement_batches_status
    CHECK (status IN ('PENDING_PAYOUT', 'POSTED', 'PAID', 'RETURNED'));

UPDATE settlement.settlement_batches b SET status = 'POSTED'
WHERE b.status = 'PENDING_PAYOUT'
  AND EXISTS (
      SELECT 1 FROM ledger.ledger_transactions t
      WHERE t.idempotency_key = 'settlement-batch-cut:' || b.settlement_batch_id
  );
