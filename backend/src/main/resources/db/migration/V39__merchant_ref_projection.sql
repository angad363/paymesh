-- ============================================================================
--  V39__merchant_ref_projection.sql
--  ADR-039: land the merchant reference projection and drop the last
--  boundary-crossing foreign key. Two moves, in this order:
--
--    1. CREATE a `merchant_ref` read model in every real service schema that
--       carried a `* -> merchants` FK (payment, ledger, settlement, risk,
--       webhook, engagement). A three-column cache of merchant identity+status,
--       fed at runtime by merchant.* lifecycle events through the inbox.
--    2. DROP every cross-capability `fk_*_merchant` constraint. Its read is now
--       served by the projection (the platform merchant-status gate) and its
--       insert-time guard is redundant on the authenticated write path; the
--       per-row is_prefixed_id CHECK on merchant_id stays untouched (ADR-029).
--
--  STILL ONE PROCESS. Nothing is extracted. This only removes the last thing
--  that made a consumer read the `merchants` table, so a later extraction lifts
--  a service with its projection already carved and warm.
--
--  Flyway runs pinned to `public` (application.yaml), so tables here are
--  SCHEMA-QUALIFIED -- unqualified names would not resolve now that V38 moved
--  every table into its service schema. is_prefixed_id lives in public (V38 left
--  the shared functions there) and is qualified for the same reason.
--
--  WHAT THIS DOES NOT DO, on purpose:
--    * No projection in `platform`, and its outbox_events/idempotency_records
--      merchant FKs are KEPT, not dropped. They are not consumers reading merchant
--      status, so the projection replaces nothing for them; dropping the FK now
--      would leave a merchant reference guarded by nothing (ADR-038 section 3).
--      `platform` splits per-service at extraction (ADR-038 section 5), and the FK
--      goes then, with the tables. The format CHECK on merchant_id stays regardless.
--    * It does not touch `merchant`'s own FKs. api_credentials, kyc_submissions
--      and merchant_status_history reference merchants WITHIN the merchant schema
--      (ADR-038 section 1) -- same capability, free referential integrity, kept.
--    * No GRANT statements. V38's ALTER DEFAULT PRIVILEGES already covers a table
--      a later migration adds to a service schema (it named this very table), so
--      each merchant_ref is granted to its <schema>_svc role automatically and
--      SchemaIsolationTest's fence stays complete.
-- ============================================================================

-- --- 1. The projection, one copy per consuming service schema ----------------
-- A DO loop rather than six near-identical CREATE TABLEs: the shape is one thing,
-- stated once. Constraint names repeat across schemas, which is fine -- an
-- index-backed constraint is unique within ITS schema, and each table is in a
-- different one.
DO $$
DECLARE
    s        text;
    schemas  text[] := ARRAY['payment','ledger','settlement','risk','webhook','engagement'];
BEGIN
    FOREACH s IN ARRAY schemas LOOP
        EXECUTE format($ddl$
            CREATE TABLE %I.merchant_ref (
                -- The merchant this row caches. Same opaque prefixed id as everywhere,
                -- same shape CHECK -- the projection needs the shape, never the foreign
                -- table (which is the whole reason the FK can go).
                merchant_id  TEXT        NOT NULL,

                -- The merchant's lifecycle status as last projected. Mirrors the
                -- merchant domain enum; a CHECK rather than a real enum type because
                -- this is a cache, and a cache should not own a type the authority owns.
                status       TEXT        NOT NULL,

                -- occurredAt of the lifecycle event that last wrote this row, so a
                -- consumer can reason about staleness and a later event never loses to
                -- an earlier one out of order (the projector guards on this).
                updated_at   TIMESTAMPTZ NOT NULL,

                CONSTRAINT pk_merchant_ref PRIMARY KEY (merchant_id),
                CONSTRAINT ck_merchant_ref_id
                    CHECK (public.is_prefixed_id(merchant_id, 'mrc_')),
                CONSTRAINT ck_merchant_ref_status
                    CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','CLOSED'))
            )
        $ddl$, s);

        -- BACKFILL. The lifecycle outbox is new in this migration's PR, so a
        -- merchant that already existed emitted no event and would never appear
        -- in the projection -- leaving it permanently UNKNOWN, i.e. a forever 503
        -- from the gate. Seed each copy from the authoritative table (still in the
        -- process, this migration only drops the FK, not the table). Empty on a
        -- fresh database; correct on any existing one. status/updated_at columns
        -- and the status CHECK set are identical to merchant.merchants (V1).
        EXECUTE format($seed$
            INSERT INTO %I.merchant_ref (merchant_id, status, updated_at)
            SELECT merchant_id, status, updated_at FROM merchant.merchants
        $seed$, s);
    END LOOP;
END $$;

-- --- 2. Drop every cross-capability `* -> merchants` FK ----------------------
-- Enumerated, not looped: each drop is a deliberate boundary cut, and a reviewer
-- should see the exact list. Grouped by the schema the table now lives in (V38).
-- The per-row is_prefixed_id CHECK on each merchant_id column is NOT dropped.

-- payment (customers, payment_method_tokens, orders, payment_intents, refunds).
-- The composite `(merchant_id, customer_id) -> customers` FKs on orders and
-- payment_method_tokens STAY (within payment); only the arrow into merchants is cut.
ALTER TABLE payment.customers             DROP CONSTRAINT fk_customers_merchant;
ALTER TABLE payment.payment_method_tokens DROP CONSTRAINT fk_payment_method_tokens_merchant;
ALTER TABLE payment.orders                DROP CONSTRAINT fk_orders_merchant;
ALTER TABLE payment.payment_intents       DROP CONSTRAINT fk_payment_intents_merchant;
ALTER TABLE payment.refunds               DROP CONSTRAINT fk_refunds_merchant;

-- ledger. The debits=credits and immutability triggers are untouched -- they
-- never referenced merchants, only ledger_* rows within this schema.
ALTER TABLE ledger.ledger_accounts     DROP CONSTRAINT fk_ledger_accounts_merchant;
ALTER TABLE ledger.ledger_transactions DROP CONSTRAINT fk_ledger_transactions_merchant;

-- settlement
ALTER TABLE settlement.settlement_configs DROP CONSTRAINT fk_settlement_configs_merchant;
ALTER TABLE settlement.settlement_batches DROP CONSTRAINT fk_settlement_batches_merchant;

-- risk
ALTER TABLE risk.risk_assessments DROP CONSTRAINT fk_risk_assessments_merchant;
ALTER TABLE risk.denylist_entries DROP CONSTRAINT fk_denylist_entries_merchant;

-- webhook
ALTER TABLE webhook.webhook_endpoints  DROP CONSTRAINT fk_webhook_endpoints_merchant;
ALTER TABLE webhook.webhook_events     DROP CONSTRAINT fk_webhook_events_merchant;
ALTER TABLE webhook.webhook_deliveries DROP CONSTRAINT fk_webhook_deliveries_merchant;

-- engagement
ALTER TABLE engagement.notifications  DROP CONSTRAINT fk_notifications_merchant;
ALTER TABLE engagement.report_facts   DROP CONSTRAINT fk_report_facts_merchant;
ALTER TABLE engagement.report_exports DROP CONSTRAINT fk_report_exports_merchant;

-- platform (outbox_events, idempotency_records): FK KEPT, deliberately. These are
-- not consumers reading merchant status, so the projection replaces nothing for
-- them -- dropping the FK now would leave the exact unguarded interval ADR-038
-- section 3 refused (a merchant reference with no replacement guard). They are the
-- one schema that splits PER-SERVICE at extraction (ADR-038 section 5), and the FK
-- is most naturally dropped then, when each service takes its own outbox/inbox.
-- OutboxTransactionIntegrationTest still proves the outbox FK refuses a bad merchant.
