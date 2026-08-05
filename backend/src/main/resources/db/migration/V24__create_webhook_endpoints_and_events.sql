-- ============================================================================
--  V24__create_webhook_endpoints_and_events.sql
--  Where PayMesh sends, and what it sends. ADR-028, SDD section 18.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  THE COLUMN THAT IS NOT HERE IS THE POINT. Every conventional webhook schema
--  carries an encrypted per-endpoint signing secret. This one carries
--  secret_version INT and nothing else, because the secret is DERIVED rather
--  than stored:
--
--      secret = "whsec_" + Base64Url(HMAC-SHA256(masterKey,
--                   "paymesh.webhook.v1|" + endpoint_id + "|" + secret_version
--                   || 0x01))
--
--  ApiCredential (ADR-022) looks like the precedent and is not: it stores a
--  HASH, which works only because verifying an inbound secret never needs the
--  plaintext back. A signing secret must be REPRODUCED on every send, so a hash
--  is useless and the real choice was reversible storage versus derivation.
--  Reversible storage means a cipher subsystem this repo does not have -- there
--  is no Cipher, no AES and no key management anywhere in V1..V23 -- for an
--  identical worst case, since one leaked master key and one leaked encryption
--  key expose exactly the same set. ADR-028 section 2.
--
--  WHAT THAT BUYS BEYOND A DELETED SUBSYSTEM. A derivable secret can always be
--  recomputed, so the two routes that return it need no stored response to
--  replay on a retry -- which matters, because idempotency_records.response_body
--  (V4) persists response bodies verbatim, and registering those routes would
--  have written the secret to the database in cleartext. The thing this schema
--  avoids storing would have been stored one table over. ADR-028 section 8.
-- ============================================================================


-- ----------------------------------------------------------------------------
--  webhook_endpoints -- where a merchant wants to be told (SDD 18.4)
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_endpoints (
    -- "whe_" + UUID (ADR-003), reserved for this capability from the start.
    endpoint_id                 VARCHAR(40)              NOT NULL,

    merchant_id                 VARCHAR(40)              NOT NULL,

    -- The merchant's URL. Length is generous because query strings in webhook
    -- URLs are common (a routing token, a tenant discriminator).
    url                         VARCHAR(2048)            NOT NULL,

    -- THE WHOLE SECRET STORY. Rotation is an increment. See the header.
    secret_version              INTEGER                  NOT NULL DEFAULT 1,

    -- A rotation leaves the OLD version signing alongside the new one until it
    -- expires, because a single signature cannot verify under two secrets and a
    -- merchant needs a window to deploy their new verifier. During the window
    -- the header carries two v1= values; the merchant accepts if either
    -- matches. Both columns are NULL outside a window. ADR-028 section 2.3.
    previous_secret_version     INTEGER,
    previous_secret_expires_at  TIMESTAMPTZ,

    -- The event types this endpoint wants, as a JSON array of strings.
    --
    -- JSONB RATHER THAN TEXT[], and rather than a child table. There are no
    -- array columns anywhere in V1..V23 and no SqlTypes.ARRAY in the codebase,
    -- so an array column would be the first thing to test Hibernate's array
    -- validation against ddl-auto=validate -- and a failure there surfaces at
    -- context startup in every integration test at once. An @ElementCollection
    -- child table is the other precedent (user_roles) and costs a fourth table
    -- plus the delete-all-and-recreate flush that produced ADR-027's deadlock.
    -- jsonb is the mapping five entities in this repo already prove.
    --
    -- The cost, stated: a jsonb array cannot be a SQL predicate through JPA, so
    -- the fan-out filters in Java over the merchant's endpoints. That is the
    -- real reason the twenty-endpoint cap matters. ADR-028 section 5.
    subscriptions               JSONB                    NOT NULL,

    status                      VARCHAR(20)              NOT NULL,

    -- Consecutive DEAD deliveries -- deliveries that exhausted their own retry
    -- budget, not individual failed attempts. One dead delivery increments this
    -- by exactly one; any success resets it to zero. At twenty, the endpoint is
    -- disabled. The distinction is a factor of five in when that happens, so it
    -- is stated here as well as in ADR-028 section 6.
    consecutive_failures        INTEGER                  NOT NULL DEFAULT 0,

    -- Optimistic lock, the same reason payment_intents has one (V8): two
    -- concurrent rotations must not both read version N and both write N+1.
    version                     BIGINT                   NOT NULL DEFAULT 0,

    created_at                  TIMESTAMPTZ              NOT NULL,
    updated_at                  TIMESTAMPTZ              NOT NULL,

    CONSTRAINT pk_webhook_endpoints PRIMARY KEY (endpoint_id),

    CONSTRAINT fk_webhook_endpoints_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),

    -- REDUNDANT WITH THE PRIMARY KEY, AND THAT REDUNDANCY IS THE POINT -- the
    -- same call V5 and V8 made. V25's webhook_deliveries carries a composite
    -- foreign key on (merchant_id, endpoint_id) so a delivery cannot reference
    -- another tenant's endpoint, and PostgreSQL will only accept that FK if a
    -- unique constraint covers exactly those two columns.
    CONSTRAINT uq_webhook_endpoints_merchant_endpoint
        UNIQUE (merchant_id, endpoint_id),

    -- Two endpoints at one URL would fan out twice to the same place: the
    -- merchant's handler sees every event doubled, and their own idempotency is
    -- the only thing between that and a doubled fulfilment.
    --
    -- CREATE IS NOT IDEMPOTENT, and an earlier version of this comment claimed
    -- it was -- that a retried create would find the existing row and re-derive
    -- the same secret. It does not: it answers 409 and returns no secret, which
    -- WebhookIntegrationTest.refusesASecondEndpointAtOneUrl pins.
    --
    -- The 409 is the right answer and the claim was the wrong argument. Handing
    -- the secret back to whoever POSTs a URL that already exists turns create
    -- into "reveal this endpoint's secret", which is exactly the thing showing
    -- it once is for. The recovery path for a lost create response is to rotate,
    -- which IS idempotent because the caller names the version it is replacing.
    -- That is what lets this route stay off the IdempotencyFilter. ADR-028 s8.
    CONSTRAINT uq_webhook_endpoints_merchant_url
        UNIQUE (merchant_id, url),

    CONSTRAINT ck_webhook_endpoints_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),

    -- Case-insensitive on purpose: 'HTTPS://x' is a valid URL and a LIKE
    -- 'https://%' would reject it. What this CANNOT see is userinfo --
    -- https://user:pass@internal/ passes any prefix test -- so that is refused
    -- in the application instead. A regex should not be asked to parse a URL.
    CONSTRAINT ck_webhook_endpoints_url_https
        CHECK (url ~* '^https://'),

    -- The two rotation columns are meaningful only together. Either both are
    -- set (inside a window) or neither is.
    CONSTRAINT ck_webhook_endpoints_rotation_window
        CHECK (
            (previous_secret_version IS NULL AND previous_secret_expires_at IS NULL)
            OR
            (previous_secret_version IS NOT NULL AND previous_secret_expires_at IS NOT NULL)
        ),

    CONSTRAINT ck_webhook_endpoints_secret_version
        CHECK (secret_version >= 1),

    CONSTRAINT ck_webhook_endpoints_consecutive_failures
        CHECK (consecutive_failures >= 0)
);

-- "Which endpoints does this merchant have, and which are still live?" is the
-- fan-out query, and it runs inside the money path on every delivered event.
CREATE INDEX idx_webhook_endpoints_merchant_status
    ON webhook_endpoints (merchant_id, status);


-- ----------------------------------------------------------------------------
--  webhook_events -- the EXTERNAL event, frozen once (SDD 18.2)
-- ----------------------------------------------------------------------------
--
--  NOT A COPY OF outbox_events, AND THAT IS THE DESIGN. payment.succeeded on
--  the outbox is PayMesh's event, with PayMesh's field names and PayMesh's
--  internal shape. payment.succeeded on the wire is the merchant's: versioned,
--  documented, and stable across refactors that rename anything internal. A
--  translator per event type sits between them.
--
--  ONE ROW SERVES EVERY SUBSCRIBED ENDPOINT, which is what makes replay
--  correct rather than merely convenient -- see the payload column.
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_events (
    -- "whv_" + UUID. NOT "evt_": that prefix already belongs to the outbox
    -- (shared/outbox/domain/EventId), and ADR-003 exists so that a mis-routed
    -- id is REJECTED rather than silently matching a row of another type. Two
    -- unrelated types sharing a prefix destroys exactly that property.
    webhook_event_id  VARCHAR(40)              NOT NULL,

    merchant_id       VARCHAR(40)              NOT NULL,

    -- The outbox evt_ id this was translated from, and the natural key that
    -- makes the fan-out handler idempotent.
    --
    -- WHY THE INBOX IS NOT ENOUGH. processed_events stops the SAME event being
    -- applied twice. EventHandler's own contract warns that it does not stop a
    -- DIFFERENT event describing the same fact -- reconciliation re-announcing
    -- an outcome under a fresh id, say -- and it warns that a handler must be
    -- idempotent anyway. This UNIQUE is how this handler obeys that: the insert
    -- is ON CONFLICT DO NOTHING and a redelivery writes nothing.
    source_event_id   VARCHAR(40)              NOT NULL,

    event_type        VARCHAR(60)              NOT NULL,

    -- The merchant-facing contract version, always 1 in this migration. Nothing
    -- reads it yet and there is no v2 translator; it exists so that adding one
    -- later does not need a migration on a table that by then has rows.
    schema_version    INTEGER                  NOT NULL,

    -- THE SERIALIZED BODY, BYTE FOR BYTE. TEXT and NOT JSONB, and the whole
    -- replay guarantee rests on that choice.
    --
    -- A merchant verifies an HMAC computed over the body they received. Replay
    -- must therefore resend the SAME BYTES, not equivalent JSON -- one
    -- character of drift and their signature check fails. JSONB is a
    -- normalizing type: it strips whitespace, drops duplicate keys and does not
    -- preserve key order, and this repo's jsonb columns round-trip through
    -- Map<String,Object> and Jackson, so what went out on a replay would be
    -- whatever Jackson re-emitted from a rehydrated map. Storing the serialized
    -- body as text is what makes the invariant true instead of aspirational.
    -- ADR-028 section 3.1.
    payload           TEXT                     NOT NULL,

    -- Business time: when the thing happened. created_at is when PayMesh wrote
    -- the row. They differ when a redelivery or a reconciliation replay is what
    -- produced the event.
    occurred_at       TIMESTAMPTZ              NOT NULL,
    created_at        TIMESTAMPTZ              NOT NULL,

    CONSTRAINT pk_webhook_events PRIMARY KEY (webhook_event_id),

    CONSTRAINT fk_webhook_events_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),

    CONSTRAINT uq_webhook_events_source_event
        UNIQUE (source_event_id),

    CONSTRAINT ck_webhook_events_schema_version
        CHECK (schema_version >= 1)
);

CREATE INDEX idx_webhook_events_merchant_occurred
    ON webhook_events (merchant_id, occurred_at);


-- ----------------------------------------------------------------------------
--  A webhook event is immutable, for the reason a ledger entry is
-- ----------------------------------------------------------------------------
--
--  Table-level and unconditional, the same shape V15 uses for ledger_entries
--  and ledger_transactions. NOT scoped to the payload column: there is no
--  column-scoped trigger anywhere in this schema, and this table has no mutable
--  column to scope around -- it has no status and no updated_at, because
--  nothing about a published event can legitimately change.
--
--  A replay that could send different bytes from the original is not a replay.
--  This trigger is what makes editing the payload impossible rather than merely
--  discouraged, which matters because the application layer that promises byte
--  identity is exactly the layer a future refactor can quietly change.
-- ----------------------------------------------------------------------------
CREATE FUNCTION webhook_events_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Webhook events are immutable; a replay must resend the original bytes'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_webhook_events_immutable
    BEFORE UPDATE OR DELETE ON webhook_events
    FOR EACH ROW
    EXECUTE FUNCTION webhook_events_are_immutable();
