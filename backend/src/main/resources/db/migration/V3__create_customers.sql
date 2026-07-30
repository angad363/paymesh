-- ============================================================================
--  V3__create_customers.sql
--  Creates the customer capability's tables: customers (merchant-scoped buyer
--  profiles) and payment_method_tokens (references to simulated provider
--  tokens). SDD section 10.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--  Only `customers` is mapped this checkpoint; payment_method_tokens exists in
--  the schema but has no entity yet, so validation ignores it.
--
--  THE INVARIANT THIS SCHEMA EXISTS TO PROTECT: a customer belongs to exactly
--  one merchant and is never visible to another. merchant_id is therefore NOT
--  NULL on every table here, leads every composite index, and is the first
--  column of every uniqueness rule -- so "unique" always means "unique within
--  one merchant", never globally.
--
--  ON PII: SDD 10.1 requires encrypted PII with deterministic hashed lookup
--  columns. There is no key management in this project yet, so the display
--  columns below hold PLAINTEXT today (ADR-006). The shape is already the
--  encrypted one: display columns (email, phone) are separate from lookup
--  columns (email_hash, phone_hash), so introducing encryption later changes
--  what the display columns contain, not the table's structure or its indexes.
-- ============================================================================

CREATE TABLE customers (
    -- Opaque, application-generated identifier "cus_" + UUID (ADR-003).
    -- Always exactly 40 chars (4 prefix + 36 UUID). VARCHAR(40) fits it exactly.
    -- PRIMARY KEY, application-minted (CustomerId.generate), so no IDENTITY/serial.
    customer_id        VARCHAR(40)              NOT NULL,

    -- Owning tenant. Same "mrc_" + UUID shape as merchants.merchant_id.
    -- NOT NULL and foreign-keyed: a customer with no merchant, or with a merchant
    -- that does not exist, is corruption -- the DB refuses it rather than trusting
    -- every future code path to remember.
    merchant_id        VARCHAR(40)              NOT NULL,

    -- The merchant's OWN identifier for this customer (their user id, CRM key).
    -- Optional: a merchant may create a customer without one. Unique per merchant
    -- (see uq_customers_merchant_reference) -- two different merchants may both use
    -- "user-42". Postgres treats NULLs as distinct in a unique index, so any number
    -- of customers may have no reference at all, which is exactly what we want.
    merchant_reference VARCHAR(100),

    -- DISPLAY column: the email as it should be shown back to the merchant, stored
    -- already trimmed+lowercased by the domain. Plaintext today, ciphertext once
    -- ADR-006 is reversed -- which is why nothing queries it (see email_hash).
    -- VARCHAR(320) = RFC 5321 max (64 local + 1 @ + 255 domain).
    email              VARCHAR(320)             NOT NULL,

    -- LOOKUP column: deterministic SHA-256 of the normalized email, lowercase hex.
    -- Exact-match search hits this, never the display column, so search keeps
    -- working unchanged when the display column becomes ciphertext (SDD 10.6).
    -- Hex SHA-256 is always exactly 64 chars -> CHAR(64) is the precise type.
    email_hash         CHAR(64)                 NOT NULL,

    -- DISPLAY column for the customer's name. Optional -- a merchant may know only
    -- an email. No lookup hash: names are not exact-match search keys.
    name               VARCHAR(200),

    -- DISPLAY column for the phone number, stored as the merchant supplied it
    -- (trimmed). Optional. E.164 caps at 15 digits; VARCHAR(32) leaves room for
    -- the "+", separators and extensions merchants actually send.
    phone              VARCHAR(32),

    -- LOOKUP column for phone, same rule as email_hash. NULL exactly when phone is
    -- NULL -- enforced by ck_customers_phone_hash_pairing below, so a phone can
    -- never exist without its search key or vice versa.
    phone_hash         CHAR(64),

    -- Lifecycle/risk state, persisted as the enum NAME (string), never its ordinal,
    -- so reordering the Java enum cannot corrupt existing rows. BLOCKED is the
    -- state the risk.customer.blocked event drives to (SDD 10.5).
    status             VARCHAR(32)              NOT NULL,

    -- Creation / last-modification instants. TIMESTAMP WITH TIME ZONE (timestamptz)
    -- so every value is an unambiguous absolute point in time (stored as UTC),
    -- matching java.time.Instant. Supplied by the app (Clock bean), so no DB
    -- DEFAULT/trigger -- the application stays the source of time.
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_customers PRIMARY KEY (customer_id),

    -- Referential integrity for the tenant link. RESTRICT (the default) rather than
    -- CASCADE: deleting a merchant that still has customers must fail loudly, not
    -- silently erase buyer records that a ledger may later need to explain.
    CONSTRAINT fk_customers_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- The merchant's reference is unique WITHIN a merchant, not globally. merchant_id
    -- leads the key, so this index also serves "list this merchant's customers by
    -- reference". Enforced at the DB so it holds under concurrent creates that both
    -- race past the application's existsByMerchantReference check.
    CONSTRAINT uq_customers_merchant_reference UNIQUE (merchant_id, merchant_reference),

    -- Defense in depth: the DB itself rejects any status outside the known set, so a
    -- bad write from any path (not just the app) cannot poison the column.
    CONSTRAINT ck_customers_status CHECK (status IN ('ACTIVE', 'BLOCKED')),

    -- A lookup hash without its display value (or the reverse) is unsearchable or
    -- unreadable. Keep the pair honest at the DB level.
    CONSTRAINT ck_customers_phone_hash_pairing CHECK (
        (phone IS NULL AND phone_hash IS NULL)
        OR (phone IS NOT NULL AND phone_hash IS NOT NULL)
    )
);

-- Access-path indexes. Every one is composite and leads with merchant_id, because
-- every query in this capability is tenant-scoped -- a leading merchant_id means the
-- planner can never choose a plan that scans another merchant's rows.
--
-- Reading one customer by id needs no index here: pk_customers already resolves it,
-- and the merchant_id predicate is a filter on the single row it returns.

-- Exact-match search by email (SDD 10.3 GET /v1/customers), hash side only.
CREATE INDEX idx_customers_merchant_email_hash ON customers (merchant_id, email_hash);

-- Exact-match search by phone. Partial: rows with no phone are unsearchable by phone
-- anyway, so keeping them out of the index costs nothing and keeps it small when most
-- customers are email-only.
CREATE INDEX idx_customers_merchant_phone_hash ON customers (merchant_id, phone_hash)
    WHERE phone_hash IS NOT NULL;

-- Listing a merchant's customers, newest first -- the default page of the search
-- endpoint. DESC matches the sort so the index supplies the order for free.
CREATE INDEX idx_customers_merchant_created_at ON customers (merchant_id, created_at DESC);


CREATE TABLE payment_method_tokens (
    -- Opaque, application-generated identifier "pmt_" + UUID, same shape as every
    -- other public id (ADR-003). No Java type maps this table yet -- it is created
    -- now so the customer schema is complete and the FK can exist; the aggregate
    -- arrives with the attach/detach endpoints.
    payment_method_token_id VARCHAR(40)              NOT NULL,

    -- Denormalized tenant column. It is reachable through customer_id, but SDD 10.6
    -- says EVERY query includes merchant_id: carrying it here means a token query
    -- never has to join customers just to prove ownership, and the composite indexes
    -- below can lead with it.
    merchant_id             VARCHAR(40)              NOT NULL,

    customer_id             VARCHAR(40)              NOT NULL,

    -- Which simulated provider issued the token (e.g. "STRIPE_SIM"). Enum-like but
    -- deliberately left un-CHECKed: the provider catalog is owned by the provider
    -- capability, which does not exist yet, so a CHECK here would be a guess.
    provider                VARCHAR(50)              NOT NULL,

    -- The provider's opaque token. NEVER a card number: this table stores a reference
    -- to a payment instrument held elsewhere, which is the whole point of it existing
    -- (SDD 10.2 -- does not store raw card numbers).
    provider_token          VARCHAR(255)             NOT NULL,

    -- Provider-supplied stable hash of the underlying instrument. Lets a merchant see
    -- that two tokens are the same card without ever seeing the card. Hex SHA-256 by
    -- convention -> fixed width.
    fingerprint             CHAR(64)                 NOT NULL,

    -- Display details: SAFE fields only (SDD 10.6). Explicit columns rather than a
    -- JSON blob, so "safe" is enforced by the schema instead of by reviewer memory.
    brand                   VARCHAR(32),
    last_four               CHAR(4),
    expiry_month            SMALLINT,
    expiry_year             SMALLINT,

    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payment_method_tokens PRIMARY KEY (payment_method_token_id),

    -- The token belongs to a customer, who belongs to a merchant. RESTRICT again:
    -- detaching a payment method is an explicit action, never a side effect.
    CONSTRAINT fk_payment_method_tokens_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id),

    CONSTRAINT fk_payment_method_tokens_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- Attaching the same provider token twice must be a no-op, not a duplicate row.
    -- Scoped to the merchant: the same simulated token seen under two merchants is
    -- two separate instruments as far as this platform is concerned.
    CONSTRAINT uq_payment_method_tokens_provider_token UNIQUE (merchant_id, provider, provider_token),

    -- last_four is four DIGITS. CHAR(4) fixes the width; this fixes the content.
    CONSTRAINT ck_payment_method_tokens_last_four CHECK (last_four ~ '^[0-9]{4}$'),

    CONSTRAINT ck_payment_method_tokens_expiry_month CHECK (expiry_month BETWEEN 1 AND 12)
);

-- The only access path that exists today: "list this customer's payment methods",
-- always with the tenant in the predicate.
CREATE INDEX idx_payment_method_tokens_merchant_customer
    ON payment_method_tokens (merchant_id, customer_id);
