-- ============================================================================
--  V38__schema_per_service.sql
--  ADR-038: carve the one public schema into nine service schemas plus a tenth
--  `platform` schema for the shared outbox/inbox/idempotency tables. STILL ONE
--  PROCESS -- nothing is extracted; this is only where each table lives and who
--  may read it, so that a later extraction can lift a package with its schema
--  instead of splitting the database under load.
--
--  WHAT THIS DOES NOT DO, on purpose (ADR-038 sections 3 and 5):
--    * It drops NO foreign key. Postgres allows a cross-schema FK within one
--      database, so every FK -- including the pervasive `* -> merchants` one --
--      stays valid and enforcing. PR 4 drops each merchant FK in the same step it
--      lands the merchant_ref projection, so the money path is never unguarded.
--    * It leaves Flyway's history in public and the three platform tables as one
--      copy each. Splitting either buys nothing while one process owns every
--      migration and every outbox row; each service takes its own when extracted.
--    * It leaves shared functions (is_prefixed_id, the trigger functions) in
--      public. A CHECK/trigger binds its function by identity at creation, not by
--      search_path, so a moved table's constraints keep calling public.* unchanged.
--
--  ALTER TABLE ... SET SCHEMA moves the table AND its indexes, owned sequences,
--  constraints and triggers as one unit -- nothing is dropped and recreated. The
--  ledger's deferred debits=credits trigger and the immutability triggers move
--  intact with the ledger_* tables, which is the whole reason the Ledger can go
--  last and whole.
-- ============================================================================

-- --- The ten schemas --------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS merchant;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS settlement;
CREATE SCHEMA IF NOT EXISTS risk;
CREATE SCHEMA IF NOT EXISTS simulator;
CREATE SCHEMA IF NOT EXISTS webhook;
CREATE SCHEMA IF NOT EXISTS engagement;
CREATE SCHEMA IF NOT EXISTS platform;

-- --- Move each table to its service schema ----------------------------------
-- Ownership follows the CODE PACKAGE (ADR-038 section 1). Where the plan's prose
-- table disagreed, the code wins -- api_credentials is merchant's, provider_callbacks
-- and refund_callbacks are payment's.

-- identity (com.paymesh.identity)
ALTER TABLE users            SET SCHEMA identity;
ALTER TABLE user_roles       SET SCHEMA identity;
ALTER TABLE refresh_tokens   SET SCHEMA identity;
ALTER TABLE security_events  SET SCHEMA identity;

-- merchant (com.paymesh.merchant)
ALTER TABLE merchants               SET SCHEMA merchant;
ALTER TABLE merchant_status_history SET SCHEMA merchant;
ALTER TABLE api_credentials         SET SCHEMA merchant;
ALTER TABLE kyc_submissions         SET SCHEMA merchant;

-- payment (com.paymesh.customer + .order + .payment + .refund -- one service)
ALTER TABLE customers               SET SCHEMA payment;
ALTER TABLE customer_status_history SET SCHEMA payment;
ALTER TABLE payment_method_tokens   SET SCHEMA payment;
ALTER TABLE orders                  SET SCHEMA payment;
ALTER TABLE order_state_history     SET SCHEMA payment;
ALTER TABLE payment_intents         SET SCHEMA payment;
ALTER TABLE payment_attempts        SET SCHEMA payment;
ALTER TABLE payment_state_history   SET SCHEMA payment;
ALTER TABLE provider_callbacks      SET SCHEMA payment;
ALTER TABLE refunds                 SET SCHEMA payment;
ALTER TABLE refund_state_history    SET SCHEMA payment;
ALTER TABLE refund_callbacks        SET SCHEMA payment;

-- ledger (com.paymesh.ledger) -- triggers and the debits=credits check move with it
ALTER TABLE ledger_accounts     SET SCHEMA ledger;
ALTER TABLE ledger_entries      SET SCHEMA ledger;
ALTER TABLE ledger_transactions SET SCHEMA ledger;

-- settlement (com.paymesh.settlement)
ALTER TABLE settlement_configs  SET SCHEMA settlement;
ALTER TABLE settlement_batches  SET SCHEMA settlement;
ALTER TABLE settlement_items    SET SCHEMA settlement;
ALTER TABLE payouts             SET SCHEMA settlement;
ALTER TABLE payout_callbacks    SET SCHEMA settlement;

-- risk (com.paymesh.risk)
ALTER TABLE risk_assessments  SET SCHEMA risk;
ALTER TABLE denylist_entries  SET SCHEMA risk;

-- simulator (com.paymesh.simulator)
ALTER TABLE provider_payments           SET SCHEMA simulator;
ALTER TABLE provider_payouts            SET SCHEMA simulator;
ALTER TABLE provider_refunds            SET SCHEMA simulator;
ALTER TABLE provider_outbound_callbacks SET SCHEMA simulator;
ALTER TABLE provider_failure_profile    SET SCHEMA simulator;

-- webhook (com.paymesh.webhook)
ALTER TABLE webhook_endpoints  SET SCHEMA webhook;
ALTER TABLE webhook_events     SET SCHEMA webhook;
ALTER TABLE webhook_deliveries SET SCHEMA webhook;

-- engagement (com.paymesh.notification + .reporting + .audit -- one service)
ALTER TABLE notifications   SET SCHEMA engagement;
ALTER TABLE report_facts    SET SCHEMA engagement;
ALTER TABLE report_exports  SET SCHEMA engagement;
ALTER TABLE audit_events    SET SCHEMA engagement;
ALTER TABLE audit_exports   SET SCHEMA engagement;

-- platform (com.paymesh.shared) -- the three tables that stay one physical copy
-- until each service takes its own outbox/inbox/idempotency at extraction.
-- ponytail: shared platform tables in one schema; split per-service at 3B+, not now.
ALTER TABLE outbox_events        SET SCHEMA platform;
ALTER TABLE processed_events     SET SCHEMA platform;
ALTER TABLE idempotency_records  SET SCHEMA platform;

-- --- The nine restricted service roles (ADR-038 section 4) -------------------
-- Each role may touch ONLY its own schema. The single-process app does NOT connect
-- as these yet -- one Hibernate over one datasource spans all schemas, so it uses a
-- role that sees them all. These exist to (a) make the isolation guarantee a real,
-- tested grant (SchemaIsolationTest SET ROLEs to each and proves the fence), and
-- (b) be the connection role each service adopts verbatim when it is extracted.
--
-- WRAPPED so a dev role without CREATEROLE skips the roles with a notice rather than
-- failing the migration: the roles are extraction-prep, not needed for one process
-- to run. Under Testcontainers the connecting user is the superuser and this runs in
-- full. (Dev, to exercise them: ALTER ROLE paymesh_app CREATEROLE;)
DO $$
DECLARE
    svc         text;
    svc_schema  text;
    schemas     text[] := ARRAY[
        'identity','merchant','payment','ledger','settlement',
        'risk','simulator','webhook','engagement'
    ];
BEGIN
    FOREACH svc_schema IN ARRAY schemas LOOP
        svc := svc_schema || '_svc';
        -- Idempotent role creation: a shared cluster keeps roles across databases,
        -- so a re-run (or another database on the same cluster) must not error.
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = svc) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', svc);
        END IF;
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', svc_schema, svc);
        EXECUTE format(
            'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I',
            svc_schema, svc);
    END LOOP;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'V38: skipping per-service roles -- current role lacks CREATEROLE. '
                     'This is expected for a dev app role; run as a superuser (or grant '
                     'CREATEROLE) to create the restricted roles.';
END $$;
