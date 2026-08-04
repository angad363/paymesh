-- ============================================================================
--  V23__make_platform_roles_grantable.sql
--  Gives PLATFORM_ADMIN somewhere to live, by making user_roles.merchant_id
--  nullable and replacing the primary key with two partial unique indexes.
--  ADR-027.
--
--  V2 PREDICTED THIS MIGRATION, ALMOST WORD FOR WORD. Its comment on this
--  column reads: "a platform-wide grant has nowhere to live yet; when one is
--  needed, make this column nullable and replace the PK with two partial unique
--  indexes." One is needed. This is that migration.
--
--  WHY IT IS NEEDED, AND WHY IT IS NOT COSMETIC. Merchant activation is
--  PLATFORM_ADMIN-only (MerchantController), and MerchantStatusFilter refuses
--  every merchant-scoped write until a merchant is ACTIVE. So with no grantable
--  PLATFORM_ADMIN, a merchant registered through the public endpoint can never
--  be activated, and a merchant that cannot be activated can do nothing at all.
--  The onboarding path was only ever walkable because the Postman collection
--  MINTS a token with application-dev.yaml's published signing key -- honest on
--  the dev profile, and not a story that survives a real deployment.
--
--  THE CONSTRAINT IS THE INTERESTING PART, NOT THE NULLABILITY. Making the
--  column nullable alone would let a MERCHANT_ADMIN grant PLATFORM_ADMIN at
--  their own merchant, and AuthenticatedCaller.requirePlatformAdmin() used to
--  read exactly that as platform authority ("PLATFORM_ADMIN:<any merchant>").
--  That is a merchant's own staff granting themselves the power to lift their
--  own suspension. ck_user_roles_scope below makes the two mutually exclusive:
--  PLATFORM_ADMIN exists only WITHOUT a merchant, and every other role only
--  WITH one. Neither shape can be written by mistake.
--
--  SERVICE_ACCOUNT STAYS MERCHANT-SCOPED and stays ungranted by any endpoint.
--  Machines authenticate with merchant API credentials (ADR-022), which are a
--  merchant's credentials -- so a platform-wide service account would be a
--  different thing needing a different issuer. Out of scope here; the constraint
--  below keeps the door shut rather than leaving it ajar.
-- ============================================================================


-- ---------------------------------------------------------------------------
--  Step 1 -- drop the primary key.
--
--  It is (user_id, merchant_id, role), and a PostgreSQL primary key implies
--  NOT NULL on every one of its columns. The column cannot become nullable
--  while this constraint stands, so it goes first and the two partial unique
--  indexes below take over its job.
-- ---------------------------------------------------------------------------
ALTER TABLE user_roles DROP CONSTRAINT pk_user_roles;


-- ---------------------------------------------------------------------------
--  Step 2 -- the merchant scope becomes optional.
--
--  NULL now means exactly one thing: this role is platform-wide, not held at
--  any tenant. It is not "unknown" and it is not "any" -- ck_user_roles_scope
--  makes it unwritable for any role that is not platform-scoped, so the value
--  cannot drift into meaning something else later.
-- ---------------------------------------------------------------------------
ALTER TABLE user_roles ALTER COLUMN merchant_id DROP NOT NULL;


-- ---------------------------------------------------------------------------
--  Step 3 -- two partial unique indexes replace the primary key.
--
--  WHY TWO AND NOT ONE. A plain UNIQUE (user_id, merchant_id, role) would not
--  do the job: in SQL, NULL is not equal to NULL, so a unique index treats two
--  platform grants of the same role to the same user as distinct rows and lets
--  both in. The merchant-scoped half needs the merchant in the key; the
--  platform half must not have it there at all. Those are two different keys
--  over two disjoint subsets of the table, which is what a partial index is.
--
--  Together they are total: ck_user_roles_scope admits only rows where
--  merchant_id IS NULL or merchant_id IS NOT NULL, and each index covers one
--  of those. No row escapes both.
-- ---------------------------------------------------------------------------

-- The old primary key, restricted to the rows that still have a merchant.
CREATE UNIQUE INDEX uq_user_roles_merchant_scoped
    ON user_roles (user_id, merchant_id, role)
    WHERE merchant_id IS NOT NULL;

-- One grant of one platform role per user. Without the WHERE clause this index
-- would be useless for its purpose, per the NULL-inequality note above.
CREATE UNIQUE INDEX uq_user_roles_platform_scoped
    ON user_roles (user_id, role)
    WHERE merchant_id IS NULL;


-- ---------------------------------------------------------------------------
--  Step 4 -- a role's scope is decided by which role it is.
--
--  THIS IS THE CONSTRAINT THAT CLOSES THE PRIVILEGE-ESCALATION PATH the
--  nullability would otherwise open. Read it as a biconditional: PLATFORM_ADMIN
--  if and only if merchant_id IS NULL.
--
--  A merchant admin calling the merchant-access grant endpoint with
--  role=PLATFORM_ADMIN now fails here even if every application check above it
--  were removed -- which is the property worth having, because the application
--  check is the one a future refactor can delete by accident. Preferring a
--  database constraint over an application check where the choice exists is the
--  house rule (CLAUDE.md, "Persistence"), and this is the case it is for.
-- ---------------------------------------------------------------------------
ALTER TABLE user_roles ADD CONSTRAINT ck_user_roles_scope CHECK (
    (role = 'PLATFORM_ADMIN' AND merchant_id IS NULL)
    OR
    (role <> 'PLATFORM_ADMIN' AND merchant_id IS NOT NULL)
);


-- ---------------------------------------------------------------------------
--  Step 5 -- the merchant-lookup index learns to skip platform rows.
--
--  "Who belongs to merchant X?" is the tenant-scoped query the dashboard runs
--  (V2). Platform grants can never answer it -- they have no merchant -- so
--  they are excluded from the index rather than carried in it as NULL entries
--  that every scan has to step over.
-- ---------------------------------------------------------------------------
DROP INDEX idx_user_roles_merchant_id;

CREATE INDEX idx_user_roles_merchant_id
    ON user_roles (merchant_id)
    WHERE merchant_id IS NOT NULL;


-- ---------------------------------------------------------------------------
--  Step 6 -- promotion and demotion are security events.
--
--  Granting somebody authority over every tenant on the platform is the single
--  most consequential thing this API can do, and until this migration the
--  security log had no name for it -- because it could not happen. Reusing
--  USER_REACTIVATED or LOGGED_OUT would be true and useless, the same argument
--  V20 makes for the four names it added.
--
--  Both promotion and demotion end every live session, so both would otherwise
--  appear in the log as a sign-out. "Who was made platform staff, and when" has
--  to be answerable from the table that exists to answer it.
-- ---------------------------------------------------------------------------
ALTER TABLE security_events
    DROP CONSTRAINT ck_security_events_type;

ALTER TABLE security_events
    ADD CONSTRAINT ck_security_events_type CHECK (
        event_type IN (
            'USER_REGISTERED',
            'LOGIN_SUCCEEDED',
            'LOGIN_FAILED',
            'TOKEN_REFRESHED',
            'REFRESH_TOKEN_REUSE_DETECTED',
            'LOGGED_OUT',
            -- Platform scope: the human is barred from PayMesh entirely.
            'USER_SUSPENDED',
            'USER_REACTIVATED',
            'USER_CLOSED',
            -- Merchant scope: they lost their roles at ONE merchant and keep
            -- their account, which is the departed-employee case (ADR-024).
            'MERCHANT_ACCESS_REVOKED',
            -- Platform scope, and the widest grant the platform has (ADR-027).
            'PLATFORM_ADMIN_GRANTED',
            'PLATFORM_ADMIN_REVOKED'
        )
    );
