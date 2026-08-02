-- ============================================================================
--  V13__create_provider_simulator.sql
--  The Provider Simulator's own tables. SDD section 13.4, design section 2.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  READ THIS FIRST: THESE TABLES ARE THE *PROVIDER'S* TRUTH, NOT PAYMESH'S.
--
--  SDD 13.2 is explicit that the simulator does not own PayMesh payment state
--  or ledger truth, and this migration is where that claim is either kept or
--  quietly broken. Note what is absent, and that every absence is deliberate:
--
--    * NO merchant_id column, anywhere. A provider serves one API credential
--      and has never been told that tenants exist. Adding one would be the
--      first step towards the simulator making an authorization decision, and
--      it has no business making one.
--    * NO foreign key out of this migration. Not to payment_intents, not to
--      payment_attempts, not to orders. A foreign key to a PayMesh table would
--      be the coupling SDD 13.6 forbids, expressed in SQL: the simulator is
--      meant to be independently deployable so that network failure between it
--      and PayMesh is realistic, and a shared database constraint makes that
--      impossible by construction.
--    * NO write path from here into any PayMesh table. The simulator's ONLY
--      influence on PayMesh is an HTTP POST of an HMAC-signed body to
--      /internal/v1/provider-callbacks/{provider}, exactly as a third party's
--      would be.
-- ============================================================================


-- ============================================================================
--  provider_payments -- the provider's own record of a payment it was asked to
--  take. SDD 13.4.
-- ============================================================================
CREATE TABLE provider_payments (
    -- sim_pay_<uuid>. This is the value PayMesh stores as
    -- payment_attempts.provider_reference, so it is the provider's public
    -- handle on the payment and is quoted in support conversations.
    provider_payment_id     VARCHAR(50)              NOT NULL,

    -- PROVIDER-SIDE IDEMPOTENCY (SDD 13.1), AND IT IS NOT THE PLATFORM'S.
    --
    -- shared.idempotency keys on merchant + endpoint + Idempotency-Key, where
    -- the merchant comes from a VERIFIED BEARER TOKEN -- which is why that
    -- filter is ordered after Spring Security. A provider has no PayMesh
    -- account, no merchant and no token, so it can supply none of the three.
    -- Registering a /sim/v1 route in IdempotentRoutes would either fail to
    -- resolve a merchant or, worse, silently scope every provider request to
    -- whatever tenant happened to be in the context.
    --
    -- The unique constraint below is therefore this endpoint's own mechanism.
    -- The same argument is written into ProviderCallbackController for the
    -- INBOUND direction; this is its mirror image.
    idempotency_key         VARCHAR(120)             NOT NULL,

    -- SHA-256 of the canonical request tuple, hex. Same key + a DIFFERENT
    -- request is a 409, not a replay of the original.
    --
    -- Returning the original would be friendlier and is what several real
    -- providers do. It is refused here because the original may be for a
    -- different amount, and answering "your payment for 5000 succeeded" to a
    -- request for 50000 is a lie on the money path. ADR-009 reached the same
    -- conclusion for the platform layer.
    request_hash            CHAR(64)                 NOT NULL,

    -- THE CALLER'S OWN REFERENCE, ECHOED BACK. AN ADDRESS, NOT STATE.
    --
    -- In practice PayMesh puts its payment intent id here, and the simulator
    -- copies the value into the paymentIntentId field of every callback it
    -- sends. It is never interpreted, never joined on and never validated
    -- beyond a length: a real provider models exactly this as a merchant
    -- reference passthrough.
    --
    -- DELIBERATELY NOT NAMED payment_intent_id. That name would have been
    -- shorter and would have been a lie -- it would suggest this module knows
    -- what a payment intent is, and it would read to the next implementer as
    -- the natural place to hang a foreign key.
    callback_reference      VARCHAR(60)              NOT NULL,

    -- Which rail was simulated. Not behaviourally significant yet; stored
    -- because the reconciliation export is a provider truth file and a truth
    -- file that cannot say how the money moved is not one.
    method                  VARCHAR(20)              NOT NULL,

    -- THE DETERMINISTIC TEST TOKEN (SDD 13.6), which stands in for the payment
    -- instrument. NEVER A REAL CARD NUMBER, an account number, or anything
    -- derived from one: SDD 4.2 puts real cardholder data out of scope for the
    -- whole project, and a simulator is the one component where someone might
    -- reasonably think a real PAN was harmless.
    token                   VARCHAR(60)              NOT NULL,

    -- What this payment will do, RESOLVED ONCE FROM THE TOKEN AT CREATE TIME
    -- and then frozen on the row.
    --
    -- Frozen deliberately: changing provider_failure_profile mid-flight must
    -- not make a payment already in progress change its mind. A real provider
    -- does not retroactively decline something it has authorized.
    behaviour               VARCHAR(30)              NOT NULL,

    -- Money is a positive integer count of minor units plus an explicit
    -- currency, everywhere in this codebase.
    amount_minor            BIGINT                   NOT NULL,
    currency                CHAR(3)                  NOT NULL,

    -- AUTOMATIC captures on authorization; MANUAL stops at AUTHORIZED and waits
    -- for POST /sim/v1/payments/{id}/capture. This is what lets the simulator
    -- exercise PayMesh's manual-capture path.
    capture_method          VARCHAR(10)              NOT NULL,

    status                  VARCHAR(20)              NOT NULL,

    -- Running totals rather than aggregate queries, so the two CHECK
    -- constraints below can exist at all. A SUM() cannot be a CHECK.
    captured_amount_minor   BIGINT                   NOT NULL DEFAULT 0,
    refunded_amount_minor   BIGINT                   NOT NULL DEFAULT 0,

    failure_code            VARCHAR(60),
    failure_message         VARCHAR(500),

    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_provider_payments PRIMARY KEY (provider_payment_id),

    -- GLOBAL, NOT TENANT-SCOPED, and for the same structural reason
    -- pk_provider_callbacks is not merchant-leading (V10): there is no tenant
    -- on this side of the boundary to scope it by. The key is chosen by the
    -- caller, the caller is one credential, and a second row under one key is
    -- a second charge.
    --
    -- THIS CONSTRAINT IS THE GUARD. The application's look-up-first path exists
    -- only to produce a friendlier answer; the integration test races two
    -- creates on one key and asserts exactly one row, which is a test of this
    -- line and not of that code.
    CONSTRAINT uq_provider_payments_idempotency_key UNIQUE (idempotency_key),

    CONSTRAINT ck_provider_payments_amount CHECK (amount_minor > 0),

    -- A provider cannot capture more than it authorized...
    CONSTRAINT ck_provider_payments_captured CHECK (
        captured_amount_minor >= 0 AND captured_amount_minor <= amount_minor
    ),

    -- ...nor refund more than it captured. IN THE DATABASE, NOT IN THE SERVICE.
    -- The service checks it too, under a row lock, so the caller gets a 422
    -- rather than a 500 -- but the service's check is advisory and this one is
    -- not, which is the only ordering of those two that survives a race.
    CONSTRAINT ck_provider_payments_refunded CHECK (
        refunded_amount_minor >= 0 AND refunded_amount_minor <= captured_amount_minor
    ),

    CONSTRAINT ck_provider_payments_method CHECK (method IN (
        'CARD', 'UPI', 'WALLET', 'BANK'
    )),

    CONSTRAINT ck_provider_payments_capture_method CHECK (capture_method IN (
        'AUTOMATIC', 'MANUAL'
    )),

    -- The provider's OWN vocabulary, which is not PayMesh's payment intent
    -- states and must not be confused with them. TIMED_OUT is the lost-callback
    -- case: the provider believes it did something and told nobody.
    CONSTRAINT ck_provider_payments_status CHECK (status IN (
        'AUTHORIZED',
        'CAPTURED',
        'DECLINED',
        'REQUIRES_ACTION',
        'TIMED_OUT'
    )),

    CONSTRAINT ck_provider_payments_behaviour CHECK (behaviour IN (
        'SUCCEED',
        'DECLINE',
        'REQUIRE_ACTION',
        'TIMEOUT',
        'DUPLICATE_CALLBACK',
        'STALE_CALLBACK'
    ))
);

-- The reconciliation export's only access pattern: "everything this provider
-- did on this UTC day".
CREATE INDEX idx_provider_payments_created_at ON provider_payments (created_at);


-- ============================================================================
--  provider_refunds -- SDD 13.4.
--
--  NOTE WHAT THIS TABLE DOES NOT CAUSE: a callback. The receiver at
--  /internal/v1/provider-callbacks speaks only the four PAYMENT outcomes
--  (ProviderOutcome), and PayMesh's Refund capability -- with whatever
--  receiver it brings -- lands later. Enqueuing a refund callback today would
--  produce a row that can only ever retry into a 404 and end ABANDONED. The
--  refund row is the provider's truth and appears in the reconciliation
--  export; the dispatcher gains a refund row type in the PR that builds the
--  receiver. This endpoint exists now so Refund is not blocked on this module.
-- ============================================================================
CREATE TABLE provider_refunds (
    provider_refund_id      VARCHAR(50)              NOT NULL,

    -- Within the simulator, so this foreign key is wanted. It is the only kind
    -- of foreign key this migration is allowed to have.
    provider_payment_id     VARCHAR(50)              NOT NULL,

    idempotency_key         VARCHAR(120)             NOT NULL,
    request_hash            CHAR(64)                 NOT NULL,

    amount_minor            BIGINT                   NOT NULL,

    status                  VARCHAR(20)              NOT NULL,
    failure_code            VARCHAR(60),
    failure_message         VARCHAR(500),

    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_provider_refunds PRIMARY KEY (provider_refund_id),

    CONSTRAINT uq_provider_refunds_idempotency_key UNIQUE (idempotency_key),

    CONSTRAINT fk_provider_refunds_payment FOREIGN KEY (provider_payment_id)
        REFERENCES provider_payments (provider_payment_id),

    CONSTRAINT ck_provider_refunds_amount CHECK (amount_minor > 0),

    CONSTRAINT ck_provider_refunds_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_provider_refunds_created_at ON provider_refunds (created_at);


-- ============================================================================
--  provider_outbound_callbacks -- what the provider INTENDS to tell PayMesh.
--
--  THE NAME DIFFERS FROM PayMesh's INBOUND provider_callbacks (V10) ON PURPOSE.
--  Two tables sharing one name on opposite sides of one boundary is a trap: a
--  support query, a reconciliation job or a reviewer reads one believing it is
--  the other, and the two disagree BY DESIGN. A row here is what the provider
--  intends to say. A row there is what PayMesh DID about what it heard. The
--  divergence between them is the interesting thing, and it is unreadable if
--  they answer to the same name.
--
--  THIS TABLE IS THE ENTIRE REASON THE SIMULATOR IS WORTH BUILDING. An inline
--  HTTP call from the create handler would be shorter and would express NONE of
--  the failure modes this module exists to reproduce:
--
--    delayed      -> deliver_after
--    lost         -> the TIMEOUT behaviour enqueues no row at all
--    duplicate    -> two rows sharing one external_event_id
--    out of order -> a row whose occurred_at is EARLIER than a delivered one
--    retried      -> status stays PENDING and attempts climbs
--
--  Every one of those is a property of WHEN and HOW OFTEN a callback is
--  delivered, and an inline call has no opinion about either.
-- ============================================================================
CREATE TABLE provider_outbound_callbacks (
    -- sim_cb_<uuid>. The ROW's identity, and deliberately not the event's.
    outbound_callback_id    VARCHAR(60)              NOT NULL,

    -- THE PROVIDER'S EVENT ID, WHICH GOES IN THE BODY AND WHICH PayMesh
    -- DEDUPLICATES ON (pk_provider_callbacks, V10).
    --
    -- DELIBERATELY NOT UNIQUE. DO NOT "FIX" THIS.
    --
    -- Two rows sharing one value IS the duplicate-callback failure mode -- the
    -- provider re-sending an event it is not sure landed. A unique constraint
    -- here would make that scenario impossible to build, which would remove one
    -- of the five behaviours in SDD 13. The uniqueness that matters is on the
    -- RECEIVING side, where it stops the duplicate having an effect, and it is
    -- already there.
    external_event_id       VARCHAR(120)             NOT NULL,

    provider_payment_id     VARCHAR(50)              NOT NULL,

    -- Denormalised from provider_payments so the operator question "what have I
    -- told PayMesh about this payment?" is one indexless scan of one table.
    callback_reference      VARCHAR(60)              NOT NULL,

    -- The RECEIVER's vocabulary (ProviderOutcome), restated here rather than
    -- imported. See SimulatedOutcome's javadoc: importing the enum would be one
    -- line and would delete the module boundary.
    outcome                 VARCHAR(20)              NOT NULL,

    -- THE PROVIDER'S EVENT CLOCK, and the value PayMesh's monotonic ordering
    -- guard compares against payment_attempts.last_provider_event_at.
    --
    -- NOT the same thing as the signature's freshness timestamp, which is taken
    -- at DELIVERY time -- see the dispatcher. Conflating the two makes every
    -- deliberately-delayed callback arrive with a stale signature and earn a
    -- 401 that reads like a receiver bug.
    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL,

    -- The dispatcher will not pick this row up before this instant. THE
    -- DELAYED-CALLBACK KNOB, and also the retry backoff: a failed delivery
    -- pushes it forward rather than spinning.
    deliver_after           TIMESTAMP WITH TIME ZONE NOT NULL,

    -- THE EXACT JSON BYTES TO SEND, serialized once when the row is written.
    --
    -- TEXT, NOT JSONB, AND THAT IS LOAD-BEARING. A JSONB round trip normalises
    -- key order and whitespace, so the bytes read back would not be the bytes
    -- that were serialized -- and the HMAC covers bytes. Storing the string
    -- means the row IS the payload: the dispatcher signs this value and posts
    -- this value, and there is no serialization step in between for the two to
    -- drift across.
    body                    TEXT                     NOT NULL,

    status                  VARCHAR(20)              NOT NULL,
    attempts                INTEGER                  NOT NULL DEFAULT 0,

    last_attempt_at         TIMESTAMP WITH TIME ZONE,

    -- What PayMesh answered. The status code is the retry signal; the outcome
    -- is the detail, read out of the response body (ADR-012 section 6). Stored
    -- because it is the only place the simulator can observe whether its
    -- carefully-constructed duplicate actually produced a DUPLICATE.
    last_response_status    INTEGER,
    last_response_outcome   VARCHAR(32),

    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_provider_outbound_callbacks PRIMARY KEY (outbound_callback_id),

    CONSTRAINT fk_provider_outbound_callbacks_payment FOREIGN KEY (provider_payment_id)
        REFERENCES provider_payments (provider_payment_id),

    -- PENDING is due or waiting; DELIVERED got a 2xx; ABANDONED gave up after
    -- maxAttempts. There is no FAILED, because a delivery that failed is one
    -- that will be retried and PENDING already says that.
    CONSTRAINT ck_provider_outbound_callbacks_status CHECK (status IN (
        'PENDING', 'DELIVERED', 'ABANDONED'
    )),

    -- The four ProviderOutcome values and no more. A fifth here would be a
    -- callback PayMesh answers with a 400 forever.
    CONSTRAINT ck_provider_outbound_callbacks_outcome CHECK (outcome IN (
        'AUTHORIZED', 'SUCCEEDED', 'FAILED', 'REQUIRES_ACTION'
    )),

    CONSTRAINT ck_provider_outbound_callbacks_attempts CHECK (attempts >= 0)
);

-- The dispatcher's only query: the due rows, oldest deadline first.
CREATE INDEX idx_provider_outbound_callbacks_due
    ON provider_outbound_callbacks (status, deliver_after);

-- "What has this provider said about this payment?" -- the support question.
CREATE INDEX idx_provider_outbound_callbacks_reference
    ON provider_outbound_callbacks (callback_reference, created_at);


-- ============================================================================
--  provider_failure_profile -- SDD 13.1's failure injection.
--
--  ONE ROW, AND THE DATABASE SAYS SO RATHER THAN THE CODE. "There is only ever
--  one row" is exactly the kind of convention that stops being true silently,
--  and a second row would make the dispatcher's behaviour depend on which one
--  it happened to read.
--
--  PERCENTAGES ARE DELIBERATELY ABSENT. SDD 13.1 asks for "latency, timeout and
--  error percentages"; SDD 13.6 asks for DETERMINISTIC test tokens. The two
--  pull opposite ways and this is the resolution: the token is deterministic
--  and wins, the profile is ambient and applies only where the token asks for
--  nothing. A probabilistic branch in a suite that runs on every commit is a
--  flake generator, and what percentages are actually used for -- "make
--  everything decline for a while" -- is default_behaviour at its limit.
--  callback_delay_ms keeps the latency half, which is the deterministic and
--  therefore useful one.
-- ============================================================================
CREATE TABLE provider_failure_profile (
    profile_id              VARCHAR(20)              NOT NULL,

    -- What a payment gets when its token names no behaviour.
    default_behaviour       VARCHAR(30)              NOT NULL DEFAULT 'SUCCEED',

    -- Added to every enqueued callback's deliver_after. Zero means "deliver on
    -- the next dispatcher tick", which is still asynchronous -- the delay knob
    -- controls how late, not whether.
    callback_delay_ms       INTEGER                  NOT NULL DEFAULT 0,

    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_provider_failure_profile PRIMARY KEY (profile_id),

    CONSTRAINT ck_provider_failure_profile_singleton CHECK (profile_id = 'DEFAULT'),

    CONSTRAINT ck_provider_failure_profile_delay CHECK (callback_delay_ms >= 0),

    CONSTRAINT ck_provider_failure_profile_behaviour CHECK (default_behaviour IN (
        'SUCCEED',
        'DECLINE',
        'REQUIRE_ACTION',
        'TIMEOUT',
        'DUPLICATE_CALLBACK',
        'STALE_CALLBACK'
    ))
);

-- Seeded by the migration so the profile ALWAYS exists. No code path has to
-- handle its absence, and "the profile is missing" is not a state anyone has to
-- reason about.
INSERT INTO provider_failure_profile (profile_id, default_behaviour, callback_delay_ms, updated_at)
VALUES ('DEFAULT', 'SUCCEED', 0, now());
