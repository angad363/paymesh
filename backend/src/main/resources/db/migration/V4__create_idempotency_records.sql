-- ============================================================================
--  V4__create_idempotency_records.sql
--  Creates idempotency_records: the durable memory of which public writes have
--  already been attempted. SDD section 23.1, ADR-009.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  THE INVARIANT THIS TABLE EXISTS TO PROTECT: a merchant's network retry must
--  never become a second order, payment or refund. Everything below serves that
--  one sentence.
--
--  WHY THIS IS A TABLE AND NOT A CACHE ENTRY: PostgreSQL is the authority, not
--  Redis (SDD 23.2, ADR-009). A cache eviction, restart or split brain must be
--  survivable without duplicating a financial write, and only a durable,
--  transactional store gives that. Redis may later accelerate the read; it may
--  never be the thing that decides.
--
--  HOW THE RACE IS DECIDED: the application INSERTs an IN_PROGRESS row and
--  COMMITS IT BEFORE running the handler. Two simultaneous retries of the same
--  key therefore collide on the primary key below, and the DATABASE picks the
--  single winner -- no application-side lock, no read-then-write window. The
--  primary key is not an access-path optimisation here; it IS the concurrency
--  control, which is why there is no surrogate id competing with it.
-- ============================================================================

CREATE TABLE idempotency_records (
    -- Owning tenant, taken from the VERIFIED access token and never from the
    -- request. Same "mrc_" + UUID shape as merchants.merchant_id, so VARCHAR(40)
    -- fits it exactly. Leading column of the primary key: an idempotency key is
    -- meaningless outside the merchant that sent it, and two merchants
    -- independently choosing "retry-1" must not collide (or worse, replay each
    -- other's response).
    merchant_id     VARCHAR(40)              NOT NULL,

    -- The route's PATH TEMPLATE plus method, e.g. "POST /api/v1/orders" or
    -- "POST /api/v1/orders/{orderId}/cancel" -- deliberately NOT the concrete
    -- request URI. Storing the URI would scope the key per-resource, so the same
    -- key aimed at two different orders would be two independent scopes, which is
    -- not the endpoint-shaped scope the SDD specifies. VARCHAR(200) is generous
    -- for "METHOD + template" and bounded so the primary key stays small.
    endpoint        VARCHAR(200)             NOT NULL,

    -- The caller's own key. Opaque to us: we compare it, never parse it, and it
    -- is NOT a credential -- possessing one grants nothing, because merchant_id
    -- above already came from the token. 255 is the conventional header-value cap
    -- and is enforced at the edge, so an overlong key is a 400 and never a
    -- silently truncated row that would collide with a different request.
    idempotency_key VARCHAR(255)             NOT NULL,

    -- SHA-256 (lowercase hex) of the RAW request body bytes, hashed before any
    -- parsing so that two bodies differing only in whitespace are correctly two
    -- different requests. This is what separates "a retry" from "a key reused for
    -- something else": same key + different hash is a 409, never a replay.
    -- Hex SHA-256 is always exactly 64 chars -> CHAR(64) is the precise type.
    request_hash    CHAR(64)                 NOT NULL,

    -- IN_PROGRESS while the handler runs, COMPLETED once its answer is stored.
    -- There is deliberately no FAILED: an attempt that ends in a 5xx is DELETED,
    -- not marked, because the server does not know what it did and pinning the key
    -- to that non-answer would make a legitimate retry impossible (ADR-009).
    -- Persisted as the enum NAME, never its ordinal, so reordering the Java enum
    -- cannot corrupt existing rows.
    status          VARCHAR(20)              NOT NULL,

    -- The stored HTTP status to replay. SMALLINT: HTTP statuses are 100-599, so
    -- two bytes is the honest width. NULL exactly while IN_PROGRESS.
    response_status SMALLINT,

    -- The stored response body, replayed verbatim to a retry. TEXT rather than a
    -- bounded VARCHAR because a successful create can be arbitrarily large and a
    -- truncated replay would be worse than no replay. NULL is legitimate even when
    -- COMPLETED -- a 204 has no body -- which is why the pairing CHECK below keys
    -- off response_status and not this column.
    response_body   TEXT,

    -- When the attempt was registered / when its answer was stored.
    -- TIMESTAMP WITH TIME ZONE (timestamptz) so every value is an unambiguous
    -- absolute point in time (stored as UTC), matching java.time.Instant. Supplied
    -- by the app (Clock bean), so no DB DEFAULT/trigger -- the application stays
    -- the source of time. created_at is also the column a future retention/reaper
    -- job will sweep on (SDD 23.1); nothing schedules one yet.
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,

    -- The scope IS the identity: merchant + endpoint + key, in that order. There
    -- is no surrogate id and no "idm_" prefix, because nothing outside the server
    -- ever names one of these rows -- ADR-003 governs identifiers that appear in an
    -- API, and this is not one. Postgres backs this with a unique index, which is
    -- simultaneously the only access path the filter needs.
    CONSTRAINT pk_idempotency_records PRIMARY KEY (merchant_id, endpoint, idempotency_key),

    -- Referential integrity for the tenant link. RESTRICT (the default) rather than
    -- CASCADE: deleting a merchant that still has in-flight idempotency records must
    -- fail loudly rather than quietly re-opening every key it was holding.
    CONSTRAINT fk_idempotency_records_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- Defense in depth: the DB itself rejects any status outside the known set, so a
    -- bad write from any path (not just the app) cannot poison the column.
    CONSTRAINT ck_idempotency_records_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),

    -- THE CONSTRAINT THAT MATTERS MOST. It makes a half-written record
    -- unrepresentable: an IN_PROGRESS row can never carry a response (which a
    -- racing retry would replay as though the work had finished), and a COMPLETED
    -- row can never lack one (which would replay a 0-status empty answer). Without
    -- this, a crash between the INSERT and the UPDATE could leave a row that lies
    -- about having an answer.
    CONSTRAINT ck_idempotency_records_response_pairing CHECK (
        (status = 'IN_PROGRESS' AND response_status IS NULL     AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND response_status IS NOT NULL AND completed_at IS NOT NULL)
    ),

    -- A stored status the client could never have received is corruption, not data.
    CONSTRAINT ck_idempotency_records_response_status CHECK (
        response_status IS NULL OR response_status BETWEEN 100 AND 599
    )
);

-- No secondary indexes. Every read and every write in this capability is
-- "WHERE merchant_id = ? AND endpoint = ? AND idempotency_key = ?", which the
-- primary key index already answers exactly. An index that no query uses is a
-- write cost on the hottest path in the system.
