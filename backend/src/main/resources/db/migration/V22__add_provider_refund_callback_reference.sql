-- ============================================================================
--  V22__add_provider_refund_callback_reference.sql
--  Gives a simulated refund the caller's own reference, so the reconciliation
--  export can be joined back to whatever asked for the refund. ADR-026.
--
--  WHY THIS WAS MISSING, AND WHY IT BLOCKS THE JOB. provider_payments has
--  callback_reference: the caller's opaque string, echoed into every callback
--  and never interpreted (V13). PayMesh puts its payment intent id there, which
--  is how a callback -- or a reconciliation row -- is resolved back to an
--  intent. provider_refunds never got the same column, because SimulatedRefund
--  was built when PayMesh had no refund receiver at all and its own javadoc says
--  the dispatcher "gains a refund row type in the PR that builds the receiver".
--  The receiver exists now (ADR-019), and the consequence of the gap is sharper
--  than an untidy schema: a refund row in the reconciliation export names a
--  provider refund and a provider payment and NOTHING PayMesh recognises, so
--  half of the provider's daily truth is unusable by the job that reads it.
--
--  THE SIMULATOR STILL HOLDS NO REFERENCE TO PayMesh, and this column does not
--  change that. It is an opaque VARCHAR the simulator stores and echoes; it
--  never parses it, never validates its shape, and has no idea PayMesh calls the
--  value a refund id. That is exactly the arrangement callback_reference already
--  has on provider_payments, and it is why ModuleBoundaryTest's empty allowlist
--  in both directions still holds.
--
--  NULLABLE, AND IT STAYS NULLABLE. Rows written before this migration have no
--  such reference and inventing one would be fabricating provider data. A refund
--  with no reference is simply one the reconciliation job cannot resolve and
--  reports as unmatched, which is the honest outcome rather than a guess.
-- ============================================================================

ALTER TABLE provider_refunds
    ADD COLUMN callback_reference VARCHAR(120);

-- Matches provider_payments.callback_reference's length exactly. Not a foreign
-- key and not unique: it is the CALLER's namespace, the simulator does not
-- police it, and a caller that reuses one string for two refunds gets two rows
-- that both carry it -- which is the caller's bug to find in the export, not a
-- constraint violation the provider invents on its behalf.

-- The reconciliation job resolves rows by this value. No index: the export reads
-- a whole UTC day by created_at (idx_provider_refunds_created_at, V13) and joins
-- in memory, so an index here would serve no query that exists.
