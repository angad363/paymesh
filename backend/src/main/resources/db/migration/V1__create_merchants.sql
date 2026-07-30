-- ============================================================================
--  V1__create_merchants.sql
--  Creates the merchants table: the first tenant/root aggregate of PayMesh.
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity that arrives next checkpoint, because Hibernate runs ddl-auto=validate
--  and will fail fast on any drift. Column choices mirror the invariants already
--  enforced in com.paymesh.merchant.domain.Merchant / MerchantId / MerchantStatus.
-- ============================================================================

CREATE TABLE merchants (
    -- Opaque, application-generated identifier "mrc_" + UUID (ADR-003).
    -- Always exactly 40 chars (4 prefix + 36 UUID). VARCHAR(40) fits it exactly.
    -- It is the PRIMARY KEY: no surrogate sequential id is exposed, and the app
    -- mints the id (MerchantId.generate) rather than the DB, so no IDENTITY/serial.
    merchant_id       VARCHAR(40)              NOT NULL,

    -- Free-text legal/display name. Domain caps it at 200 chars, so VARCHAR(200)
    -- is the DB mirror of that invariant. NOT NULL: registration always sets it.
    business_name     VARCHAR(200)             NOT NULL,

    -- Login / contact email, stored already lowercased+trimmed by the domain.
    -- VARCHAR(320) = RFC 5321 max (64 local + 1 @ + 255 domain). NOT NULL.
    email             VARCHAR(320)             NOT NULL,

    -- ISO-3166-1 alpha-2 country code, uppercased by the domain (e.g. "US").
    -- Always exactly 2 chars -> CHAR(2) is the precise fixed-width type.
    country           CHAR(2)                  NOT NULL,

    -- ISO-4217 currency code, uppercased by the domain (e.g. "USD").
    -- Always exactly 3 chars -> CHAR(3).
    default_currency  CHAR(3)                  NOT NULL,

    -- Lifecycle state, persisted as the enum NAME (string), never its ordinal,
    -- so reordering the Java enum can never corrupt existing rows. VARCHAR(32)
    -- comfortably holds the longest value ("PENDING_VERIFICATION" = 21).
    status            VARCHAR(32)              NOT NULL,

    -- Creation / last-modification instants. TIMESTAMP WITH TIME ZONE (timestamptz)
    -- so every value is an unambiguous absolute point in time (stored as UTC),
    -- matching java.time.Instant. Values are supplied by the app (Clock bean),
    -- so no DB DEFAULT/trigger -- the application stays the source of time.
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_merchants PRIMARY KEY (merchant_id),

    -- One merchant account per email address. Enforced at the DB so it holds even
    -- under concurrent registrations that race past the application's existsByEmail
    -- check. Postgres auto-creates a unique index, which also serves email lookups.
    CONSTRAINT uq_merchants_email UNIQUE (email),

    -- Defense in depth: the DB itself rejects any status outside the known set,
    -- so a bad write from any path (not just the app) cannot poison the column.
    CONSTRAINT ck_merchants_status CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'CLOSED')
    )
);
