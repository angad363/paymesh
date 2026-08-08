-- =============================================================================
-- V26: give every opaque identifier column the format its Java type already
--      demands.
--
-- WHY THIS EXISTS
--
-- ADR-003 says public identifiers are `<prefix>_<uuid>`, and every `XxxId`
-- value object enforces it: `MerchantId.from` refuses anything that is not
-- `mrc_` followed by a canonical UUID, and throws when it sees one.
--
-- The database never agreed. Until this migration every one of these columns
-- was a bare VARCHAR carrying a primary key or a foreign key and nothing else,
-- so PostgreSQL would happily store `not-a-merchant-id` -- an identifier the
-- application cannot read back. Not a hypothetical: closing open item 2 turned
-- up five scheduled sweeps that a single such row disabled permanently and
-- silently, and the item had been filed as "needs database state the current
-- CHECKs forbid" precisely because nobody had checked whether the ids were
-- constrained. They were not.
--
-- A CHECK here is the constraint the application check was always standing in
-- for. Per CLAUDE.md: prefer a database constraint over an application check,
-- because the application check turns a violation into a readable error while
-- the constraint is what makes it true.
--
-- WHAT THIS DOES NOT DO
--
-- It does not make row mapping infallible, and the per-item try/catch the
-- sweeps gained in open item 2 stays load-bearing. `orders.metadata` is JSONB
-- with no shape constraint and maps to a `Map`, so a JSON array in that column
-- is still a row no mapper can rehydrate. Constraints narrow the input space;
-- they do not close it.
--
-- APPLIED AGAINST DATA. All 63 pairs below were checked against a populated
-- V25 database before this was written: zero violating rows. This migration
-- therefore needs no data repair step, and if it ever fails on someone else's
-- database the failure is the point -- it found an identifier nobody could
-- have read back anyway.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- The shared predicate.
--
-- ONE FUNCTION RATHER THAN THE REGEX WRITTEN 63 TIMES. Only the prefix varies
-- between these columns, so inlining the pattern would mean 63 chances to
-- fat-finger a character class in a way no test would notice -- a subtly wrong
-- regex still accepts every id the application mints, and only misbehaves on
-- the malformed row this whole migration exists to reject.
--
-- IMMUTABLE is not decoration: PostgreSQL refuses a CHECK constraint whose
-- expression is not immutable. STRICT makes a NULL input return NULL, which a
-- CHECK treats as satisfied -- that is the behaviour we want, because several
-- columns below are legitimately nullable (`orders.customer_id` on a guest
-- checkout, for one) and a NULL is an absent identifier, not a malformed one.
--
-- Lowercase hex only, and no version nibble pinned, to match `XxxId.from`
-- exactly. It does not check the UUID version, so neither does this: pinning
-- `4` would reject a v7 id the application would accept.
--
-- THE JAVA HALF DID NOT MATCH WHEN THIS WAS WRITTEN, AND WAS FIXED TO.
-- Fifteen of the eighteen id types round-tripped with `equalsIgnoreCase`, so
-- they accepted `mrc_550E8400-...` as well as `mrc_550e8400-...`; the other
-- three (`ApiCredentialId`, `KycSubmissionId`, `PaymentMethodTokenId`) called
-- `UUID.fromString` and discarded the result, which also let through padded
-- shorthand like `apc_1-1-1-1-1` that the parser silently expands. This
-- constraint was therefore STRICTER than the type it claims to mirror.
--
-- The constraint is the correct half. These columns are primary keys, and two
-- accepted spellings of one UUID is two rows for one thing. All eighteen now
-- round-trip with `equals`, so the two agree.
-- -----------------------------------------------------------------------------
CREATE FUNCTION is_prefixed_id(value text, prefix text)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $$
SELECT value ~ ('^' || prefix ||
                '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
$$;

COMMENT ON FUNCTION is_prefixed_id(text, text) IS
    'ADR-003 identifier format: <prefix> followed by a canonical lowercase UUID. '
    'Mirrors what every XxxId.from accepts. NULL in, NULL out, so a CHECK using '
    'this is satisfied by an absent identifier.';


-- -----------------------------------------------------------------------------
-- mrc_ -- the merchant, on all 23 tables that carry a tenant.
--
-- This is the one that matters most. `merchant_id` is the tenant discriminator
-- every query scopes by, so an unreadable value here is not one broken row, it
-- is a tenant whose rows cannot be loaded at all.
-- -----------------------------------------------------------------------------
ALTER TABLE api_credentials         ADD CONSTRAINT ck_api_credentials_merchant_id_format         CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE customer_status_history ADD CONSTRAINT ck_customer_status_history_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE customers               ADD CONSTRAINT ck_customers_merchant_id_format               CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE idempotency_records     ADD CONSTRAINT ck_idempotency_records_merchant_id_format     CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE kyc_submissions         ADD CONSTRAINT ck_kyc_submissions_merchant_id_format         CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE ledger_accounts         ADD CONSTRAINT ck_ledger_accounts_merchant_id_format         CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE ledger_transactions     ADD CONSTRAINT ck_ledger_transactions_merchant_id_format     CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE merchant_status_history ADD CONSTRAINT ck_merchant_status_history_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE merchants               ADD CONSTRAINT ck_merchants_merchant_id_format               CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE order_state_history     ADD CONSTRAINT ck_order_state_history_merchant_id_format     CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE orders                  ADD CONSTRAINT ck_orders_merchant_id_format                  CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE outbox_events           ADD CONSTRAINT ck_outbox_events_merchant_id_format           CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE payment_attempts        ADD CONSTRAINT ck_payment_attempts_merchant_id_format        CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE payment_intents         ADD CONSTRAINT ck_payment_intents_merchant_id_format         CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE payment_method_tokens   ADD CONSTRAINT ck_payment_method_tokens_merchant_id_format   CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE payment_state_history   ADD CONSTRAINT ck_payment_state_history_merchant_id_format   CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE provider_callbacks      ADD CONSTRAINT ck_provider_callbacks_merchant_id_format      CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE refund_state_history    ADD CONSTRAINT ck_refund_state_history_merchant_id_format    CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE refunds                 ADD CONSTRAINT ck_refunds_merchant_id_format                 CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE user_roles              ADD CONSTRAINT ck_user_roles_merchant_id_format              CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE webhook_deliveries      ADD CONSTRAINT ck_webhook_deliveries_merchant_id_format      CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE webhook_endpoints       ADD CONSTRAINT ck_webhook_endpoints_merchant_id_format       CHECK (is_prefixed_id(merchant_id, 'mrc_'));
ALTER TABLE webhook_events          ADD CONSTRAINT ck_webhook_events_merchant_id_format          CHECK (is_prefixed_id(merchant_id, 'mrc_'));

-- cus_ -- nullable wherever a guest checkout is allowed, which the CHECK permits.
ALTER TABLE customer_status_history ADD CONSTRAINT ck_customer_status_history_customer_id_format CHECK (is_prefixed_id(customer_id, 'cus_'));
ALTER TABLE customers               ADD CONSTRAINT ck_customers_customer_id_format               CHECK (is_prefixed_id(customer_id, 'cus_'));
ALTER TABLE orders                  ADD CONSTRAINT ck_orders_customer_id_format                  CHECK (is_prefixed_id(customer_id, 'cus_'));
ALTER TABLE payment_intents         ADD CONSTRAINT ck_payment_intents_customer_id_format         CHECK (is_prefixed_id(customer_id, 'cus_'));
ALTER TABLE payment_method_tokens   ADD CONSTRAINT ck_payment_method_tokens_customer_id_format   CHECK (is_prefixed_id(customer_id, 'cus_'));

-- ord_
ALTER TABLE order_state_history     ADD CONSTRAINT ck_order_state_history_order_id_format        CHECK (is_prefixed_id(order_id, 'ord_'));
ALTER TABLE orders                  ADD CONSTRAINT ck_orders_order_id_format                     CHECK (is_prefixed_id(order_id, 'ord_'));
ALTER TABLE payment_intents         ADD CONSTRAINT ck_payment_intents_order_id_format            CHECK (is_prefixed_id(order_id, 'ord_'));

-- pi_
ALTER TABLE payment_attempts        ADD CONSTRAINT ck_payment_attempts_payment_intent_id_format  CHECK (is_prefixed_id(payment_intent_id, 'pi_'));
ALTER TABLE payment_intents         ADD CONSTRAINT ck_payment_intents_payment_intent_id_format   CHECK (is_prefixed_id(payment_intent_id, 'pi_'));
ALTER TABLE payment_state_history   ADD CONSTRAINT ck_payment_state_history_intent_id_format     CHECK (is_prefixed_id(payment_intent_id, 'pi_'));
ALTER TABLE provider_callbacks      ADD CONSTRAINT ck_provider_callbacks_intent_id_format        CHECK (is_prefixed_id(payment_intent_id, 'pi_'));
ALTER TABLE refunds                 ADD CONSTRAINT ck_refunds_payment_intent_id_format           CHECK (is_prefixed_id(payment_intent_id, 'pi_'));

-- ref_
ALTER TABLE refund_callbacks        ADD CONSTRAINT ck_refund_callbacks_refund_id_format          CHECK (is_prefixed_id(refund_id, 'ref_'));
ALTER TABLE refund_state_history    ADD CONSTRAINT ck_refund_state_history_refund_id_format      CHECK (is_prefixed_id(refund_id, 'ref_'));
ALTER TABLE refunds                 ADD CONSTRAINT ck_refunds_refund_id_format                   CHECK (is_prefixed_id(refund_id, 'ref_'));

-- usr_
ALTER TABLE refresh_tokens          ADD CONSTRAINT ck_refresh_tokens_user_id_format              CHECK (is_prefixed_id(user_id, 'usr_'));
ALTER TABLE user_roles              ADD CONSTRAINT ck_user_roles_user_id_format                  CHECK (is_prefixed_id(user_id, 'usr_'));
ALTER TABLE users                   ADD CONSTRAINT ck_users_user_id_format                       CHECK (is_prefixed_id(user_id, 'usr_'));

-- The singletons: one table each.
ALTER TABLE api_credentials         ADD CONSTRAINT ck_api_credentials_id_format                  CHECK (is_prefixed_id(api_credential_id, 'apc_'));
ALTER TABLE kyc_submissions         ADD CONSTRAINT ck_kyc_submissions_id_format                  CHECK (is_prefixed_id(kyc_submission_id, 'kyc_'));
ALTER TABLE payment_attempts        ADD CONSTRAINT ck_payment_attempts_id_format                 CHECK (is_prefixed_id(payment_attempt_id, 'pat_'));
ALTER TABLE payment_method_tokens   ADD CONSTRAINT ck_payment_method_tokens_id_format            CHECK (is_prefixed_id(payment_method_token_id, 'pmt_'));

-- lac_ / ltx_ -- the ledger, where an unreadable id is an unauditable journal.
ALTER TABLE ledger_accounts         ADD CONSTRAINT ck_ledger_accounts_id_format                  CHECK (is_prefixed_id(ledger_account_id, 'lac_'));
ALTER TABLE ledger_entries          ADD CONSTRAINT ck_ledger_entries_account_id_format           CHECK (is_prefixed_id(ledger_account_id, 'lac_'));
ALTER TABLE ledger_entries          ADD CONSTRAINT ck_ledger_entries_transaction_id_format       CHECK (is_prefixed_id(ledger_transaction_id, 'ltx_'));
ALTER TABLE ledger_transactions     ADD CONSTRAINT ck_ledger_transactions_id_format              CHECK (is_prefixed_id(ledger_transaction_id, 'ltx_'));

-- evt_ -- the outbox event id, and the two places that quote it.
-- `webhook_events.source_event_id` is the outbox event a webhook event was
-- translated from, so it carries the outbox prefix rather than a webhook one.
ALTER TABLE outbox_events           ADD CONSTRAINT ck_outbox_events_event_id_format              CHECK (is_prefixed_id(event_id, 'evt_'));
ALTER TABLE processed_events        ADD CONSTRAINT ck_processed_events_event_id_format           CHECK (is_prefixed_id(event_id, 'evt_'));
ALTER TABLE webhook_events          ADD CONSTRAINT ck_webhook_events_source_event_id_format      CHECK (is_prefixed_id(source_event_id, 'evt_'));

-- whv_ / whe_ / whd_ -- webhook. Three prefixes, and they are easy to confuse:
-- whv_ is the EVENT (the fact), whe_ is the ENDPOINT (the destination), whd_ is
-- the DELIVERY (one attempt to put one at the other).
ALTER TABLE webhook_deliveries      ADD CONSTRAINT ck_webhook_deliveries_event_id_format         CHECK (is_prefixed_id(webhook_event_id, 'whv_'));
ALTER TABLE webhook_events          ADD CONSTRAINT ck_webhook_events_id_format                   CHECK (is_prefixed_id(webhook_event_id, 'whv_'));
ALTER TABLE webhook_deliveries      ADD CONSTRAINT ck_webhook_deliveries_endpoint_id_format      CHECK (is_prefixed_id(endpoint_id, 'whe_'));
ALTER TABLE webhook_endpoints       ADD CONSTRAINT ck_webhook_endpoints_id_format                CHECK (is_prefixed_id(endpoint_id, 'whe_'));
ALTER TABLE webhook_deliveries      ADD CONSTRAINT ck_webhook_deliveries_id_format               CHECK (is_prefixed_id(delivery_id, 'whd_'));

-- sim_pay_ / sim_ref_ / sim_cb_ -- the simulator, which deliberately does NOT
-- reuse PayMesh's prefixes: `sim_ref_` exists so a provider refund id can never
-- be mistaken for one of PayMesh's own `ref_` rows.
ALTER TABLE provider_outbound_callbacks ADD CONSTRAINT ck_provider_outbound_callbacks_payment_id_format CHECK (is_prefixed_id(provider_payment_id, 'sim_pay_'));
ALTER TABLE provider_payments           ADD CONSTRAINT ck_provider_payments_id_format                   CHECK (is_prefixed_id(provider_payment_id, 'sim_pay_'));
ALTER TABLE provider_refunds            ADD CONSTRAINT ck_provider_refunds_payment_id_format            CHECK (is_prefixed_id(provider_payment_id, 'sim_pay_'));
ALTER TABLE provider_refunds            ADD CONSTRAINT ck_provider_refunds_id_format                    CHECK (is_prefixed_id(provider_refund_id, 'sim_ref_'));
ALTER TABLE provider_outbound_callbacks ADD CONSTRAINT ck_provider_outbound_callbacks_id_format         CHECK (is_prefixed_id(outbound_callback_id, 'sim_cb_'));


-- =============================================================================
-- DELIBERATELY NOT CONSTRAINED, and each for a different reason. Listed so the
-- next person to read this file does not have to work out whether they were
-- missed or excluded.
--
--   outbox_events.aggregate_id     Polymorphic by `aggregate_type`: ord_, pi_,
--                                  ref_ AND cus_ (AttachPaymentMethodTokenService
--                                  emits a CUSTOMER aggregate). Already documents
--                                  why it carries no foreign key either.
--
--   ledger_transactions.reference_id   Polymorphic by `reference_type`, which
--                                  LedgerTransaction defines as exactly two
--                                  values -- PAYMENT_INTENT and REFUND -- so this
--                                  holds pi_ or ref_ and never ord_.
--
--                                  For both: a CHECK could name the union, but it
--                                  would need editing every time a new aggregate
--                                  emits an event, which is a constraint that
--                                  silently rots. Note the two lists differ from
--                                  each other; an earlier draft of this comment
--                                  gave them one shared list and got both wrong.
--
--   *_state_history.actor_id       Not an identifier. Its own comment says "a
--   *_status_history.actor_id      merchant id, a provider name" -- whichever is
--                                  knowable, and NULL when a SYSTEM sweeper acted.
--
--   refresh_tokens.family_id       Bare UUIDs, CHAR(36), no prefix. V2 says so
--   security_events.event_id       explicitly: internal only, never exposed, so
--                                  ADR-003's prefix rule does not apply. Note
--                                  `security_events.event_id` is NOT the outbox
--                                  `event_id` constrained above despite the name.
--
--   provider_callbacks.external_event_id      The PROVIDER's identifier. We
--   provider_outbound_callbacks.external_event_id  neither mint these nor get to
--   refund_callbacks.external_event_id        say what they look like.
--
--   idempotency_records.idempotency_key       Merchant-supplied, opaque to us.
--   kyc_submissions.registration_id           "What the merchant claims. Free
--                                             text, never interpreted, bounded."
--
--   provider_failure_profile.profile_id       A singleton constant, not a UUID.
--
--   *_history_id, ledger_entries.ledger_entry_id   Sequence-backed internal keys,
--                                             not the opaque public ids ADR-003
--                                             governs. They are never exposed.
-- =============================================================================
