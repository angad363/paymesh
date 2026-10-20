-- Adds POSTED to the batch lifecycle: PENDING_PAYOUT -> POSTED (the Ledger journalled the cut,
-- ledger.batch_posted) -> PAID | RETURNED. A payout is submittable only once its batch is POSTED
-- (ADR-047 section 8).
--
-- BACKFILL: only batches whose cut journal the Ledger already posted become POSTED; the rest stay
-- PENDING_PAYOUT and reach POSTED via ledger.batch_posted (ADR-047 section 8). The journal lives in
-- the Ledger's schema, which this service does not own, so the one-time read is guarded: where the
-- table is absent or not readable by this role (a fresh database has no batches either), nothing
-- is backfilled. On the shared dev cluster the monolith's V41 has already done this.
ALTER TABLE settlement_batches DROP CONSTRAINT IF EXISTS ck_settlement_batches_status;
ALTER TABLE settlement_batches ADD CONSTRAINT ck_settlement_batches_status
    CHECK (status IN ('PENDING_PAYOUT', 'POSTED', 'PAID', 'RETURNED'));

DO $$
BEGIN
    IF to_regclass('ledger.ledger_transactions') IS NOT NULL
       AND has_table_privilege('ledger.ledger_transactions', 'SELECT') THEN
        EXECUTE $q$
            UPDATE settlement_batches b SET status = 'POSTED'
            WHERE b.status = 'PENDING_PAYOUT'
              AND EXISTS (
                  SELECT 1 FROM ledger.ledger_transactions t
                  WHERE t.idempotency_key = 'settlement-batch-cut:' || b.settlement_batch_id
              )
        $q$;
    END IF;
END
$$;
