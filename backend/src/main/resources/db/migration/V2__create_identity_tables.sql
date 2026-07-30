-- ============================================================================
--  V2__create_identity_tables.sql
--  Creates the Identity and Access schema (SDD section 8.4): the human/service
--  identities that authenticate against PayMesh, the roles they hold, the
--  rotating refresh-token sessions they open, and the security audit trail.
--
--  Schema is authored by hand (Flyway-owned). Hibernate runs ddl-auto=validate,
--  so every column here must match the mapped JPA entity exactly or startup
--  fails. Column choices mirror the invariants enforced in
--  com.paymesh.identity.domain (User / UserId / Role / RefreshToken / ...).
--
--  Two tables from SDD 8.4 are deliberately absent:
--    * roles          -- the Role enum is the definition; a lookup table would
--                        add a join and a second source of truth for four
--                        values that only ever change with a code deploy.
--    * oauth_accounts -- OAuth2/OIDC federation is out of scope this checkpoint.
-- ============================================================================


-- ---------------------------------------------------------------------------
--  users -- one row per human identity that can log in.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    -- Opaque, application-generated identifier "usr_" + UUID (ADR-003).
    -- Always exactly 40 chars (4 prefix + 36 UUID). Minted by UserId.generate(),
    -- never by the database, so no IDENTITY/serial.
    user_id        VARCHAR(40)              NOT NULL,

    -- Login email, stored already lowercased+trimmed by the domain.
    -- VARCHAR(320) = RFC 5321 max (64 local + 1 @ + 255 domain), matching the
    -- merchants table.
    --
    -- DIVERGENCE FROM THE SDD: 8.4 lists this column as `email_hash`. PayMesh
    -- stores it in the clear, for the same reasons merchants.email is in the
    -- clear: login has to report "email already registered" on signup, operators
    -- have to be able to find an account, and a one-way hash makes both
    -- impossible while protecting a value the merchants table already holds.
    -- Revisit if PayMesh ever has to pseudonymize PII at rest.
    email          VARCHAR(320)             NOT NULL,

    -- BCrypt output ("$2a$10$" + 53 chars = 60). Sized to 255 so a future move
    -- to Argon2/scrypt (longer encodings) is a code change, not a migration.
    -- Only ever written by the password hasher; never returned by the API.
    password_hash  VARCHAR(255)             NOT NULL,

    -- Lifecycle state, persisted as the enum NAME (string) and never its ordinal,
    -- so reordering the Java enum cannot corrupt existing rows.
    status         VARCHAR(32)              NOT NULL,

    -- Absolute instants (stored as UTC), matching java.time.Instant. Supplied by
    -- the application's Clock bean, so no DB DEFAULT: the app owns time.
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_users PRIMARY KEY (user_id),

    -- One account per email. Enforced at the DB so it holds even when two
    -- registrations race past the application's existsByEmail check. Postgres
    -- backs this with a unique index, which is also the index every login uses
    -- to find the account -- the hot lookup path at millions of users.
    CONSTRAINT uq_users_email UNIQUE (email),

    CONSTRAINT ck_users_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')
    )
);


-- ---------------------------------------------------------------------------
--  user_roles -- merchant-scoped role membership (SDD 8.4 "Scoped membership").
--  Modelled as a JPA @ElementCollection owned by the user aggregate: roles have
--  no identity or lifecycle of their own, they are part of the user.
-- ---------------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id      VARCHAR(40) NOT NULL,

    -- Role enum NAME. PLATFORM_ADMIN and SERVICE_ACCOUNT are declared in the
    -- enum but not grantable yet: both are platform-wide, and this table
    -- requires a merchant scope (see merchant_id below).
    role         VARCHAR(32) NOT NULL,

    -- The tenant this role is scoped to. NOT NULL, which is what lets the
    -- three columns form the primary key without a surrogate. Consequence: a
    -- platform-wide grant has nowhere to live yet; when one is needed, make this
    -- column nullable and replace the PK with two partial unique indexes.
    --
    -- No FOREIGN KEY to merchants on purpose. Identity and Merchant are separate
    -- services in the target architecture (ADR-001) and a cross-module FK would
    -- have to be dropped the day one of them is extracted. The reference is
    -- validated in the domain (MerchantId), not by the database.
    merchant_id  VARCHAR(40) NOT NULL,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, merchant_id, role),

    -- Deleting an identity takes its memberships with it; there is no meaning to
    -- a role held by nobody.
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    CONSTRAINT ck_user_roles_role CHECK (
        role IN ('PLATFORM_ADMIN', 'MERCHANT_ADMIN', 'MERCHANT_USER', 'SERVICE_ACCOUNT')
    )
);

-- "Who belongs to merchant X?" is the tenant-scoped query the dashboard runs.
-- The primary key is (user_id, merchant_id, role), so its index cannot serve a
-- merchant_id-only lookup -- that leading column is missing. This one can.
CREATE INDEX idx_user_roles_merchant_id ON user_roles (merchant_id);


-- ---------------------------------------------------------------------------
--  refresh_tokens -- one row per issued refresh token, forming rotation
--  families. Rotation means: spending a token revokes it and mints its
--  successor in the same family. A second attempt to spend an
--  already-revoked token is theft evidence, and revokes the whole family.
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    -- SHA-256 of the token, lowercase hex: exactly 64 chars -> CHAR(64).
    -- The plaintext token NEVER reaches this table; it exists only in the login
    -- response. SHA-256 rather than BCrypt on purpose: the token is 256 bits of
    -- SecureRandom, so there is no low-entropy secret to slow an attacker down,
    -- and refresh has to find the row by hash -- which a salted, deliberately
    -- slow hash cannot do without scanning the table.
    --
    -- It is the PRIMARY KEY, so Postgres indexes it for free and every refresh
    -- is a single index lookup.
    token_hash  CHAR(64)                 NOT NULL,

    -- Rotation family. Every token minted from a given login shares this id, so
    -- reuse detection can revoke the entire lineage in one UPDATE. Random UUID,
    -- internal only -- never exposed, so ADR-003's prefix rule does not apply.
    family_id   CHAR(36)                 NOT NULL,

    -- Owner of the session. Denormalized onto every token so "log this user out
    -- everywhere" never needs a join.
    user_id     VARCHAR(40)              NOT NULL,

    issued_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,

    -- NULL means live. Set when the token is rotated, when the family is revoked
    -- after detected reuse, or on logout. Rows are never deleted: the history is
    -- what makes reuse detectable, and a cleanup job can prune long-expired rows
    -- later.
    revoked_at  TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (token_hash),

    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    -- A token cannot expire before it was issued.
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at)
);

-- Reuse detection revokes by family: UPDATE ... WHERE family_id = ?.
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);

-- "Revoke every session for this user" (password change, account suspension).
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);


-- ---------------------------------------------------------------------------
--  security_events -- append-only audit trail (SDD 8.4 / 8.6). Written on every
--  authentication outcome. Nothing in the request path reads it; it exists for
--  monitoring and incident review, and later feeds identity.login.failed.
-- ---------------------------------------------------------------------------
CREATE TABLE security_events (
    -- Random UUID. Internal, never exposed over the API, so no ADR-003 prefix.
    event_id    CHAR(36)                 NOT NULL,

    event_type  VARCHAR(48)              NOT NULL,

    -- Who the event is about. The user id when the account is known, otherwise
    -- the email that was attempted -- a failed login against an unknown address
    -- is exactly the event worth recording, and dropping the address would make
    -- credential-stuffing invisible. VARCHAR(320) holds either.
    actor       VARCHAR(320)             NULL,

    -- SHA-256 of the caller's IP, lowercase hex -> CHAR(64). Hashed, not stored
    -- raw: correlating repeated attempts from one source only needs equality,
    -- and an IP is personal data. NULL when the address is unavailable.
    ip_hash     CHAR(64)                 NULL,

    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_security_events PRIMARY KEY (event_id),

    CONSTRAINT ck_security_events_type CHECK (
        event_type IN (
            'USER_REGISTERED',
            'LOGIN_SUCCEEDED',
            'LOGIN_FAILED',
            'TOKEN_REFRESHED',
            'REFRESH_TOKEN_REUSE_DETECTED',
            'LOGGED_OUT'
        )
    )
);

-- The two audit questions: "what happened to this actor?" and "what happened in
-- this window?". Actor first so the composite serves both the actor-scoped
-- lookup and its time ordering.
CREATE INDEX idx_security_events_actor_occurred_at
    ON security_events (actor, occurred_at DESC);
