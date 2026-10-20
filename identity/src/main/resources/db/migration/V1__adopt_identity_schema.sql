-- ============================================================================
--  V1__adopt_identity_schema.sql
--  Identity's own tables: users, user_roles, refresh_tokens, security_events
--  (SDD 8.4, ADR-023, ADR-024, ADR-026, ADR-027), schema-per-service
--  (ADR-038), the extraction (ADR-045).
--
--  THIS MODULE'S OWN FLYWAY HISTORY, SEPARATE FROM THE MONOLITH'S (ADR-045,
--  following the ADR-041/042/043/044 recipe). These four tables already exist
--  in the shared cluster's `identity` schema: they are the monolith's V2
--  (create), V20 and V23 (ALTERs), and V26 (identifier-format CHECKs), all
--  moved into this schema by V38. This file is byte-for-byte that live shape,
--  so a FRESH database (a new environment, a Testcontainers run) gets the
--  same schema either way. Against the EXISTING shared dev database,
--  `baseline-on-migrate` (application.yaml) makes Flyway ADOPT the
--  already-present tables at this version instead of re-running CREATE TABLE
--  against a schema that already has them.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  UNLIKE WEBHOOK'S/ENGAGEMENT'S/RISK'S OWN V1, THERE IS NO FOREIGN KEY TO
--  `merchants` TO DROP HERE. The monolith's own V2 said so explicitly when it
--  created `user_roles`: "No FOREIGN KEY to merchants on purpose. Identity and
--  Merchant are separate services in the target architecture (ADR-001) and a
--  cross-module FK would have to be dropped the day one of them is
--  extracted." That day is this PR, and there is nothing to drop -- V39's
--  merchant_ref fan-out never touched `user_roles` either, for the same
--  reason. The identifier-format CHECKs on every merchant_id/user_id column
--  (V26) are kept; they were never a cross-schema reference to begin with.
-- ============================================================================


-- --- the shared identifier-format predicate (V26 in the monolith) ----------
-- ADR-003's <prefix>_<uuid> shape, enforced in the database rather than only
-- in each XxxId value object's compact constructor. Defined in `public` (not
-- `identity`) because it is a platform-wide predicate several schemas rely
-- on, exactly where the monolith's own V26 put it. CREATE OR REPLACE, not
-- CREATE: on a database that already has it (any environment sharing a
-- cluster with the monolith) this is a same-definition no-op; on a genuinely
-- fresh one (Testcontainers) it is the only place this module defines it --
-- the same call provider-sim's, webhook's, engagement's and risk's own V1
-- each make.
CREATE OR REPLACE FUNCTION public.is_prefixed_id(value text, prefix text)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $$
SELECT value ~ ('^' || prefix ||
                '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
$$;


-- ---------------------------------------------------------------------------
--  users -- one row per human identity that can log in.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    user_id        VARCHAR(40)              NOT NULL,
    email          VARCHAR(320)             NOT NULL,
    password_hash  VARCHAR(255)             NOT NULL,
    status         VARCHAR(32)              NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_users PRIMARY KEY (user_id),

    CONSTRAINT uq_users_email UNIQUE (email),

    CONSTRAINT ck_users_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')
    ),

    CONSTRAINT ck_users_user_id_format CHECK (is_prefixed_id(user_id, 'usr_'))
);


-- ---------------------------------------------------------------------------
--  user_roles -- merchant-scoped OR platform-wide role membership (ADR-027).
--  Modelled as a JPA @ElementCollection owned by the user aggregate: roles
--  have no identity or lifecycle of their own, they are part of the user.
-- ---------------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id      VARCHAR(40) NOT NULL,

    role         VARCHAR(32) NOT NULL,

    -- NULL means platform-wide (ADR-027/V23). No FOREIGN KEY to merchants --
    -- see the file header. Not validated against a reachable merchants table;
    -- only its FORMAT is (below).
    merchant_id  VARCHAR(40),

    -- The pre-V23 primary key (user_id, merchant_id, role) is GONE: a NOT
    -- NULL column cannot hold the platform-wide grant this table now allows.
    -- The two partial unique indexes below do the primary key's job instead.
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    CONSTRAINT ck_user_roles_role CHECK (
        role IN ('PLATFORM_ADMIN', 'MERCHANT_ADMIN', 'MERCHANT_USER', 'SERVICE_ACCOUNT')
    ),

    -- ADR-027: PLATFORM_ADMIN iff merchant_id IS NULL. Closes the
    -- privilege-escalation path nullability alone would open.
    CONSTRAINT ck_user_roles_scope CHECK (
        (role = 'PLATFORM_ADMIN' AND merchant_id IS NULL)
        OR
        (role <> 'PLATFORM_ADMIN' AND merchant_id IS NOT NULL)
    ),

    CONSTRAINT ck_user_roles_user_id_format CHECK (is_prefixed_id(user_id, 'usr_')),
    CONSTRAINT ck_user_roles_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

-- The old primary key, restricted to the rows that still have a merchant.
CREATE UNIQUE INDEX uq_user_roles_merchant_scoped
    ON user_roles (user_id, merchant_id, role)
    WHERE merchant_id IS NOT NULL;

-- One grant of one platform role per user.
CREATE UNIQUE INDEX uq_user_roles_platform_scoped
    ON user_roles (user_id, role)
    WHERE merchant_id IS NULL;

-- "Who belongs to merchant X?" -- the tenant-scoped query the dashboard runs.
-- Platform grants can never answer it, so they are excluded rather than
-- carried in the index as NULL entries every scan has to step over.
CREATE INDEX idx_user_roles_merchant_id
    ON user_roles (merchant_id)
    WHERE merchant_id IS NOT NULL;


-- ---------------------------------------------------------------------------
--  refresh_tokens -- one row per issued refresh token, forming rotation
--  families.
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    token_hash  CHAR(64)                 NOT NULL,
    family_id   CHAR(36)                 NOT NULL,
    user_id     VARCHAR(40)              NOT NULL,
    issued_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at  TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (token_hash),

    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at),

    CONSTRAINT ck_refresh_tokens_user_id_format CHECK (is_prefixed_id(user_id, 'usr_'))
);

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);


-- ---------------------------------------------------------------------------
--  security_events -- append-only audit trail (SDD 8.4 / 8.6, ADR-024,
--  ADR-027).
-- ---------------------------------------------------------------------------
CREATE TABLE security_events (
    event_id    CHAR(36)                 NOT NULL,
    event_type  VARCHAR(48)              NOT NULL,
    actor       VARCHAR(320)             NULL,
    ip_hash     CHAR(64)                 NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_security_events PRIMARY KEY (event_id),

    -- The full, final list (V2 + V20 + V23): session lifecycle, the two
    -- platform-scope actions (USER_SUSPENDED/REACTIVATED/CLOSED), the one
    -- merchant-scope action (MERCHANT_ACCESS_REVOKED), and platform-admin
    -- promotion/demotion.
    CONSTRAINT ck_security_events_type CHECK (
        event_type IN (
            'USER_REGISTERED',
            'LOGIN_SUCCEEDED',
            'LOGIN_FAILED',
            'TOKEN_REFRESHED',
            'REFRESH_TOKEN_REUSE_DETECTED',
            'LOGGED_OUT',
            'USER_SUSPENDED',
            'USER_REACTIVATED',
            'USER_CLOSED',
            'MERCHANT_ACCESS_REVOKED',
            'PLATFORM_ADMIN_GRANTED',
            'PLATFORM_ADMIN_REVOKED'
        )
    )
);

CREATE INDEX idx_security_events_actor_occurred_at
    ON security_events (actor, occurred_at DESC);
