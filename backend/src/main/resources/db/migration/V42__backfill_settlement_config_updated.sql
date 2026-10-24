-- ============================================================================
--  V42__backfill_settlement_config_updated.sql
--  ADR-051: the extracted ledger service projects each merchant's holding
--  period from `settlement.config.updated`. Settlement emitted no such event
--  before this release, so merchants configured earlier would project as the
--  platform default (and release early). This emits one event per existing
--  settlement_configs row so the relay delivers them to the ledger service.
--
--  ADDITIVE DATA MIGRATION: no schema change. Same envelope and payload as
--  GetSettlementConfigService (aggregate SETTLEMENT_CONFIG, version 1). The
--  projection is idempotent and last-write-wins on occurred_at, so a re-run or
--  a later real change is safe. Must be relayed BEFORE the ledger release flag
--  is flipped (ADR-051 consequences).
-- ============================================================================
INSERT INTO platform.outbox_events (
    event_id, merchant_id, aggregate_type, aggregate_id, event_type,
    event_version, payload, occurred_at
)
SELECT
    'evt_' || gen_random_uuid()::text,
    c.merchant_id,
    'SETTLEMENT_CONFIG',
    c.merchant_id,
    'settlement.config.updated',
    1,
    jsonb_build_object(
        'merchantId', c.merchant_id,
        'holdingPeriodSeconds', c.holding_period_seconds,
        -- the row's real last-change time, not deploy time, so a future audit consumer of
        -- settlement.config.updated sees genuine change times (the projection only snapshots the
        -- latest value, so either works for it; the >= guard stays safe).
        'occurredAt', to_char(c.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
    ),
    c.updated_at
FROM settlement.settlement_configs c;
