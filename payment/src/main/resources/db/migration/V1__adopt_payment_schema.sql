-- ============================================================================
--  V1__adopt_payment_schema.sql
--  The payment service's own tables: customers, customer_status_history,
--  payment_method_tokens, orders, order_state_history, payment_intents,
--  payment_attempts, payment_state_history, provider_callbacks, refunds,
--  refund_state_history, refund_callbacks, and the merchant_ref projection
--  (ADR-038 schema-per-service, ADR-039, ADR-048).
--
--  THIS MODULE'S OWN FLYWAY HISTORY (ADR-041..047 recipe). These tables already
--  exist in the shared cluster's `payment` schema: the monolith's V3, V5, V6, V8..V13,
--  V16..V19, V22, V26 (identifier CHECKs) and V39 (merchant_ref), moved into this
--  schema by V38. This file is that live shape (reconstructed from a pg_dump of the
--  live `payment` schema), so a FRESH database gets the same schema. Against the
--  EXISTING shared dev database, `baseline-on-migrate` makes Flyway ADOPT the present
--  tables at this version and this file is not re-run.
--
--  The refund triggers (ADR-032 style deferred invariant) live INSIDE the `payment`
--  schema; their functions are in `public` exactly as the monolith's V16 put them, and
--  resolve their unqualified table names through the caller's search_path
--  (`payment, public`). NO FOREIGN KEY CROSSES A SCHEMA: every FK below stays inside
--  `payment`; there is no FK to a merchants table (merchant_ref replaced it, ADR-039).
-- ============================================================================

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

CREATE OR REPLACE FUNCTION public.refunds_assert_within_captured()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    active_total BIGINT;
    captured     BIGINT;
BEGIN
    SELECT COALESCE(SUM(r.amount_minor), 0)
      INTO active_total
      FROM refunds r
     WHERE r.payment_intent_id = NEW.payment_intent_id
       AND r.status NOT IN ('FAILED', 'CANCELLED');

    SELECT p.captured_amount_minor
      INTO captured
      FROM payment_intents p
     WHERE p.payment_intent_id = NEW.payment_intent_id;

    IF captured IS NULL THEN
        RAISE EXCEPTION
            'Refund % names payment intent %, which does not exist',
            NEW.refund_id, NEW.payment_intent_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF active_total > captured THEN
        RAISE EXCEPTION
            'Refunds of payment intent % total % which exceeds the % captured',
            NEW.payment_intent_id, active_total, captured
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION public.refunds_assert_currency_matches()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    payment_currency CHAR(3);
BEGIN
    SELECT p.currency
      INTO payment_currency
      FROM payment_intents p
     WHERE p.payment_intent_id = NEW.payment_intent_id;

    IF payment_currency IS NOT NULL AND payment_currency <> NEW.currency THEN
        RAISE EXCEPTION
            'Refund % is in % but payment intent % is in %',
            NEW.refund_id, NEW.currency, NEW.payment_intent_id, payment_currency
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$function$;


CREATE TABLE customer_status_history (
    customer_status_history_id bigint NOT NULL,
    merchant_id character varying(40) NOT NULL,
    customer_id character varying(40) NOT NULL,
    from_status character varying(20),
    to_status character varying(20) NOT NULL,
    actor_type character varying(20) NOT NULL,
    actor_id character varying(80),
    reason character varying(200),
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_customer_status_history_actor CHECK (((actor_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT ck_customer_status_history_actor_id CHECK (((((actor_type)::text = 'MERCHANT'::text) AND (actor_id IS NOT NULL)) OR (((actor_type)::text = 'SYSTEM'::text) AND (actor_id IS NULL)))),
    CONSTRAINT ck_customer_status_history_customer_id_format CHECK (public.is_prefixed_id((customer_id)::text, 'cus_'::text)),
    CONSTRAINT ck_customer_status_history_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_customer_status_history_status CHECK (((to_status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'BLOCKED'::character varying])::text[])))
);

CREATE SEQUENCE customer_status_history_customer_status_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE customer_status_history_customer_status_history_id_seq OWNED BY customer_status_history.customer_status_history_id;

CREATE TABLE customers (
    customer_id character varying(40) NOT NULL,
    merchant_id character varying(40) NOT NULL,
    merchant_reference character varying(100),
    email character varying(320) NOT NULL,
    email_hash character(64) NOT NULL,
    name character varying(200),
    phone character varying(32),
    phone_hash character(64),
    status character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_customers_customer_id_format CHECK (public.is_prefixed_id((customer_id)::text, 'cus_'::text)),
    CONSTRAINT ck_customers_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_customers_phone_hash_pairing CHECK ((((phone IS NULL) AND (phone_hash IS NULL)) OR ((phone IS NOT NULL) AND (phone_hash IS NOT NULL)))),
    CONSTRAINT ck_customers_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'BLOCKED'::character varying])::text[])))
);

CREATE TABLE merchant_ref (
    merchant_id text NOT NULL,
    status text NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_merchant_ref_id CHECK (public.is_prefixed_id(merchant_id, 'mrc_'::text)),
    CONSTRAINT ck_merchant_ref_status CHECK ((status = ANY (ARRAY['PENDING_VERIFICATION'::text, 'ACTIVE'::text, 'SUSPENDED'::text, 'CLOSED'::text])))
);

CREATE TABLE order_state_history (
    order_state_history_id bigint NOT NULL,
    merchant_id character varying(40) NOT NULL,
    order_id character varying(40) NOT NULL,
    from_status character varying(32),
    to_status character varying(32) NOT NULL,
    actor_type character varying(20) NOT NULL,
    actor_id character varying(80),
    reason character varying(200),
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_order_state_history_actor CHECK (((actor_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT ck_order_state_history_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_order_state_history_order_id_format CHECK (public.is_prefixed_id((order_id)::text, 'ord_'::text))
);

ALTER TABLE order_state_history ALTER COLUMN order_state_history_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME order_state_history_order_state_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE orders (
    order_id character varying(40) NOT NULL,
    merchant_id character varying(40) NOT NULL,
    customer_id character varying(40),
    merchant_order_ref character varying(100),
    amount_minor bigint NOT NULL,
    currency character(3) NOT NULL,
    amount_paid_minor bigint NOT NULL,
    status character varying(32) NOT NULL,
    description character varying(500),
    metadata jsonb,
    expires_at timestamp with time zone,
    cancellation_reason character varying(200),
    cancelled_at timestamp with time zone,
    version integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_orders_amount_paid CHECK (((amount_paid_minor >= 0) AND (amount_paid_minor <= amount_minor))),
    CONSTRAINT ck_orders_amount_positive CHECK ((amount_minor > 0)),
    CONSTRAINT ck_orders_cancellation CHECK (((((status)::text = 'CANCELLED'::text) AND (cancelled_at IS NOT NULL)) OR (((status)::text <> 'CANCELLED'::text) AND (cancelled_at IS NULL)))),
    CONSTRAINT ck_orders_currency CHECK ((currency ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT ck_orders_customer_id_format CHECK (public.is_prefixed_id((customer_id)::text, 'cus_'::text)),
    CONSTRAINT ck_orders_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_orders_order_id_format CHECK (public.is_prefixed_id((order_id)::text, 'ord_'::text)),
    CONSTRAINT ck_orders_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PAID'::character varying, 'PARTIALLY_PAID'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);

CREATE TABLE payment_attempts (
    payment_attempt_id character varying(40) NOT NULL,
    merchant_id character varying(40) NOT NULL,
    payment_intent_id character varying(40) NOT NULL,
    attempt_number integer NOT NULL,
    provider character varying(50) NOT NULL,
    provider_reference character varying(120),
    status character varying(32) NOT NULL,
    amount_minor bigint NOT NULL,
    currency character(3) NOT NULL,
    failure_code character varying(60),
    failure_message character varying(500),
    last_provider_event_at timestamp with time zone,
    request_payload jsonb,
    response_payload jsonb,
    version integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_payment_attempts_amount CHECK ((amount_minor > 0)),
    CONSTRAINT ck_payment_attempts_currency CHECK ((currency ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT ck_payment_attempts_id_format CHECK (public.is_prefixed_id((payment_attempt_id)::text, 'pat_'::text)),
    CONSTRAINT ck_payment_attempts_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_payment_attempts_number CHECK ((attempt_number > 0)),
    CONSTRAINT ck_payment_attempts_payment_intent_id_format CHECK (public.is_prefixed_id((payment_intent_id)::text, 'pi_'::text)),
    CONSTRAINT ck_payment_attempts_status CHECK (((status)::text = ANY ((ARRAY['PROCESSING'::character varying, 'REQUIRES_ACTION'::character varying, 'AUTHORIZED'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE payment_intents (
    payment_intent_id character varying(40) NOT NULL,
    merchant_id character varying(40) NOT NULL,
    order_id character varying(40) NOT NULL,
    customer_id character varying(40),
    amount_minor bigint NOT NULL,
    currency character(3) NOT NULL,
    capture_method character varying(16) NOT NULL,
    payment_method_type character varying(20),
    status character varying(32) NOT NULL,
    captured_amount_minor bigint NOT NULL,
    refunded_amount_minor bigint NOT NULL,
    failure_code character varying(60),
    failure_message character varying(500),
    cancellation_reason character varying(200),
    cancelled_at timestamp with time zone,
    description character varying(500),
    metadata jsonb,
    version integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_payment_intents_amount CHECK ((amount_minor > 0)),
    CONSTRAINT ck_payment_intents_cancelled_at CHECK (((((status)::text = 'CANCELLED'::text) AND (cancelled_at IS NOT NULL)) OR (((status)::text <> 'CANCELLED'::text) AND (cancelled_at IS NULL)))),
    CONSTRAINT ck_payment_intents_capture_method CHECK (((capture_method)::text = ANY ((ARRAY['AUTOMATIC'::character varying, 'MANUAL'::character varying])::text[]))),
    CONSTRAINT ck_payment_intents_captured CHECK (((captured_amount_minor >= 0) AND (captured_amount_minor <= amount_minor))),
    CONSTRAINT ck_payment_intents_currency CHECK ((currency ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT ck_payment_intents_customer_id_format CHECK (public.is_prefixed_id((customer_id)::text, 'cus_'::text)),
    CONSTRAINT ck_payment_intents_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_payment_intents_method_known CHECK ((((status)::text = ANY ((ARRAY['REQUIRES_PAYMENT_METHOD'::character varying, 'CANCELLED'::character varying])::text[])) OR (payment_method_type IS NOT NULL))),
    CONSTRAINT ck_payment_intents_order_id_format CHECK (public.is_prefixed_id((order_id)::text, 'ord_'::text)),
    CONSTRAINT ck_payment_intents_payment_intent_id_format CHECK (public.is_prefixed_id((payment_intent_id)::text, 'pi_'::text)),
    CONSTRAINT ck_payment_intents_refunded CHECK (((refunded_amount_minor >= 0) AND (refunded_amount_minor <= captured_amount_minor))),
    CONSTRAINT ck_payment_intents_status CHECK (((status)::text = ANY ((ARRAY['REQUIRES_PAYMENT_METHOD'::character varying, 'REQUIRES_CONFIRMATION'::character varying, 'PROCESSING'::character varying, 'REQUIRES_ACTION'::character varying, 'AUTHORIZED'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'PARTIALLY_REFUNDED'::character varying, 'REFUNDED'::character varying])::text[]))),
    CONSTRAINT ck_payment_intents_succeeded_captured CHECK ((((status)::text <> 'SUCCEEDED'::text) OR (captured_amount_minor > 0)))
);

CREATE TABLE payment_method_tokens (
    payment_method_token_id character varying(40) NOT NULL,
    merchant_id character varying(40) NOT NULL,
    customer_id character varying(40) NOT NULL,
    provider character varying(50) NOT NULL,
    provider_token character varying(255) NOT NULL,
    fingerprint character(64) NOT NULL,
    brand character varying(32),
    last_four character(4),
    expiry_month smallint,
    expiry_year smallint,
    created_at timestamp with time zone NOT NULL,
    detached_at timestamp with time zone,
    CONSTRAINT ck_payment_method_tokens_customer_id_format CHECK (public.is_prefixed_id((customer_id)::text, 'cus_'::text)),
    CONSTRAINT ck_payment_method_tokens_expiry_month CHECK (((expiry_month >= 1) AND (expiry_month <= 12))),
    CONSTRAINT ck_payment_method_tokens_id_format CHECK (public.is_prefixed_id((payment_method_token_id)::text, 'pmt_'::text)),
    CONSTRAINT ck_payment_method_tokens_last_four CHECK ((last_four ~ '^[0-9]{4}$'::text)),
    CONSTRAINT ck_payment_method_tokens_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text))
);

CREATE TABLE payment_state_history (
    payment_state_history_id bigint NOT NULL,
    merchant_id character varying(40) NOT NULL,
    payment_intent_id character varying(40) NOT NULL,
    from_status character varying(32),
    to_status character varying(32) NOT NULL,
    actor_type character varying(20) NOT NULL,
    actor_id character varying(80),
    reason character varying(200),
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_payment_state_history_actor CHECK (((actor_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'PROVIDER'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT ck_payment_state_history_intent_id_format CHECK (public.is_prefixed_id((payment_intent_id)::text, 'pi_'::text)),
    CONSTRAINT ck_payment_state_history_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text))
);

ALTER TABLE payment_state_history ALTER COLUMN payment_state_history_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME payment_state_history_payment_state_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE provider_callbacks (
    provider character varying(50) NOT NULL,
    external_event_id character varying(120) NOT NULL,
    merchant_id character varying(40) NOT NULL,
    payment_intent_id character varying(40) NOT NULL,
    payload_hash character(64) NOT NULL,
    payload jsonb NOT NULL,
    outcome character varying(32) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_provider_callbacks_intent_id_format CHECK (public.is_prefixed_id((payment_intent_id)::text, 'pi_'::text)),
    CONSTRAINT ck_provider_callbacks_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_provider_callbacks_outcome CHECK (((outcome)::text = ANY ((ARRAY['APPLIED'::character varying, 'IGNORED_STALE'::character varying, 'IGNORED_TERMINAL'::character varying])::text[]))),
    CONSTRAINT ck_provider_callbacks_processed_at CHECK ((processed_at >= received_at))
);

CREATE TABLE refund_callbacks (
    provider character varying(40) NOT NULL,
    external_event_id character varying(100) NOT NULL,
    refund_id character varying(40) NOT NULL,
    outcome character varying(20) NOT NULL,
    payload_hash character(64) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_refund_callbacks_outcome CHECK (((outcome)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_refund_callbacks_refund_id_format CHECK (public.is_prefixed_id((refund_id)::text, 'ref_'::text))
);

CREATE TABLE refund_state_history (
    refund_state_history_id bigint NOT NULL,
    merchant_id character varying(40) NOT NULL,
    refund_id character varying(40) NOT NULL,
    from_status character varying(20),
    to_status character varying(20) NOT NULL,
    actor_type character varying(20) NOT NULL,
    actor_id character varying(80),
    reason character varying(200),
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_refund_state_history_actor CHECK (((actor_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'PROVIDER'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT ck_refund_state_history_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_refund_state_history_refund_id_format CHECK (public.is_prefixed_id((refund_id)::text, 'ref_'::text))
);

CREATE SEQUENCE refund_state_history_refund_state_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE refund_state_history_refund_state_history_id_seq OWNED BY refund_state_history.refund_state_history_id;

CREATE TABLE refunds (
    refund_id character varying(40) NOT NULL,
    merchant_id character varying(40) NOT NULL,
    payment_intent_id character varying(40) NOT NULL,
    amount_minor bigint NOT NULL,
    currency character(3) NOT NULL,
    status character varying(20) NOT NULL,
    merchant_reference character varying(100),
    reason character varying(200),
    provider_reference character varying(100),
    failure_code character varying(64),
    failure_message character varying(500),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_refunds_amount_positive CHECK ((amount_minor > 0)),
    CONSTRAINT ck_refunds_currency CHECK ((currency ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT ck_refunds_merchant_id_format CHECK (public.is_prefixed_id((merchant_id)::text, 'mrc_'::text)),
    CONSTRAINT ck_refunds_payment_intent_id_format CHECK (public.is_prefixed_id((payment_intent_id)::text, 'pi_'::text)),
    CONSTRAINT ck_refunds_refund_id_format CHECK (public.is_prefixed_id((refund_id)::text, 'ref_'::text)),
    CONSTRAINT ck_refunds_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);

ALTER TABLE ONLY customer_status_history ALTER COLUMN customer_status_history_id SET DEFAULT nextval('customer_status_history_customer_status_history_id_seq'::regclass);

ALTER TABLE ONLY refund_state_history ALTER COLUMN refund_state_history_id SET DEFAULT nextval('refund_state_history_refund_state_history_id_seq'::regclass);

ALTER TABLE ONLY customer_status_history
    ADD CONSTRAINT pk_customer_status_history PRIMARY KEY (customer_status_history_id);

ALTER TABLE ONLY customers
    ADD CONSTRAINT pk_customers PRIMARY KEY (customer_id);

ALTER TABLE ONLY merchant_ref
    ADD CONSTRAINT pk_merchant_ref PRIMARY KEY (merchant_id);

ALTER TABLE ONLY order_state_history
    ADD CONSTRAINT pk_order_state_history PRIMARY KEY (order_state_history_id);

ALTER TABLE ONLY orders
    ADD CONSTRAINT pk_orders PRIMARY KEY (order_id);

ALTER TABLE ONLY payment_attempts
    ADD CONSTRAINT pk_payment_attempts PRIMARY KEY (payment_attempt_id);

ALTER TABLE ONLY payment_intents
    ADD CONSTRAINT pk_payment_intents PRIMARY KEY (payment_intent_id);

ALTER TABLE ONLY payment_method_tokens
    ADD CONSTRAINT pk_payment_method_tokens PRIMARY KEY (payment_method_token_id);

ALTER TABLE ONLY payment_state_history
    ADD CONSTRAINT pk_payment_state_history PRIMARY KEY (payment_state_history_id);

ALTER TABLE ONLY provider_callbacks
    ADD CONSTRAINT pk_provider_callbacks PRIMARY KEY (provider, external_event_id);

ALTER TABLE ONLY refund_callbacks
    ADD CONSTRAINT pk_refund_callbacks PRIMARY KEY (provider, external_event_id);

ALTER TABLE ONLY refund_state_history
    ADD CONSTRAINT pk_refund_state_history PRIMARY KEY (refund_state_history_id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT pk_refunds PRIMARY KEY (refund_id);

ALTER TABLE ONLY customers
    ADD CONSTRAINT uq_customers_merchant_customer UNIQUE (merchant_id, customer_id);

ALTER TABLE ONLY customers
    ADD CONSTRAINT uq_customers_merchant_reference UNIQUE (merchant_id, merchant_reference);

ALTER TABLE ONLY orders
    ADD CONSTRAINT uq_orders_merchant_order UNIQUE (merchant_id, order_id);

ALTER TABLE ONLY orders
    ADD CONSTRAINT uq_orders_merchant_ref UNIQUE (merchant_id, merchant_order_ref);

ALTER TABLE ONLY payment_attempts
    ADD CONSTRAINT uq_payment_attempts_intent_number UNIQUE (payment_intent_id, attempt_number);

ALTER TABLE ONLY payment_attempts
    ADD CONSTRAINT uq_payment_attempts_merchant_attempt UNIQUE (merchant_id, payment_attempt_id);

ALTER TABLE ONLY payment_intents
    ADD CONSTRAINT uq_payment_intents_merchant_intent UNIQUE (merchant_id, payment_intent_id);

ALTER TABLE ONLY payment_method_tokens
    ADD CONSTRAINT uq_payment_method_tokens_provider_token UNIQUE (merchant_id, provider, provider_token);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT uq_refunds_merchant_reference UNIQUE (merchant_id, merchant_reference);

CREATE INDEX idx_customers_merchant_created_at ON customers USING btree (merchant_id, created_at DESC);

CREATE INDEX idx_customers_merchant_email_hash ON customers USING btree (merchant_id, email_hash);

CREATE INDEX idx_customers_merchant_phone_hash ON customers USING btree (merchant_id, phone_hash) WHERE (phone_hash IS NOT NULL);

CREATE INDEX idx_order_state_history_order ON order_state_history USING btree (merchant_id, order_id, occurred_at);

CREATE INDEX idx_orders_expirable ON orders USING btree (expires_at) WHERE (((status)::text = 'PENDING'::text) AND (expires_at IS NOT NULL));

CREATE INDEX idx_orders_merchant_created_at ON orders USING btree (merchant_id, created_at DESC, order_id DESC);

CREATE INDEX idx_orders_merchant_customer ON orders USING btree (merchant_id, customer_id) WHERE (customer_id IS NOT NULL);

CREATE INDEX idx_orders_merchant_status ON orders USING btree (merchant_id, status);

CREATE INDEX idx_payment_attempts_intent ON payment_attempts USING btree (merchant_id, payment_intent_id, attempt_number DESC);

CREATE INDEX idx_payment_intents_merchant_created_at ON payment_intents USING btree (merchant_id, created_at DESC, payment_intent_id DESC);

CREATE INDEX idx_payment_intents_merchant_customer_created ON payment_intents USING btree (merchant_id, customer_id, created_at);

CREATE INDEX idx_payment_intents_merchant_order ON payment_intents USING btree (merchant_id, order_id);

CREATE INDEX idx_payment_intents_merchant_status ON payment_intents USING btree (merchant_id, status);

CREATE INDEX idx_payment_intents_processing_since ON payment_intents USING btree (updated_at) WHERE ((status)::text = 'PROCESSING'::text);

CREATE INDEX idx_payment_method_tokens_merchant_customer ON payment_method_tokens USING btree (merchant_id, customer_id);

CREATE INDEX idx_payment_state_history_intent ON payment_state_history USING btree (merchant_id, payment_intent_id, occurred_at);

CREATE INDEX idx_provider_callbacks_intent ON provider_callbacks USING btree (merchant_id, payment_intent_id, received_at DESC);

CREATE INDEX ix_customer_status_history_customer ON customer_status_history USING btree (customer_id, occurred_at DESC);

CREATE INDEX ix_payment_method_tokens_customer_live ON payment_method_tokens USING btree (customer_id, created_at DESC) WHERE (detached_at IS NULL);

CREATE INDEX ix_refund_callbacks_refund ON refund_callbacks USING btree (refund_id, occurred_at DESC);

CREATE INDEX ix_refund_state_history_refund ON refund_state_history USING btree (refund_id, occurred_at);

CREATE INDEX ix_refunds_merchant ON refunds USING btree (merchant_id, created_at DESC, refund_id DESC);

CREATE INDEX ix_refunds_payment_intent ON refunds USING btree (payment_intent_id, status);

CREATE UNIQUE INDEX uq_payment_attempts_provider_reference ON payment_attempts USING btree (provider, provider_reference) WHERE (provider_reference IS NOT NULL);

CREATE UNIQUE INDEX uq_payment_intents_live_per_order ON payment_intents USING btree (merchant_id, order_id) WHERE ((status)::text <> ALL ((ARRAY['FAILED'::character varying, 'CANCELLED'::character varying])::text[]));

CREATE UNIQUE INDEX uq_payment_method_tokens_live_fingerprint ON payment_method_tokens USING btree (customer_id, fingerprint) WHERE (detached_at IS NULL);

CREATE TRIGGER tr_refunds_currency_matches BEFORE INSERT OR UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION public.refunds_assert_currency_matches();

CREATE CONSTRAINT TRIGGER tr_refunds_within_captured AFTER INSERT OR UPDATE ON refunds DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.refunds_assert_within_captured();

ALTER TABLE ONLY customer_status_history
    ADD CONSTRAINT fk_customer_status_history_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id);

ALTER TABLE ONLY order_state_history
    ADD CONSTRAINT fk_order_state_history_order FOREIGN KEY (merchant_id, order_id) REFERENCES orders(merchant_id, order_id);

ALTER TABLE ONLY orders
    ADD CONSTRAINT fk_orders_customer FOREIGN KEY (merchant_id, customer_id) REFERENCES customers(merchant_id, customer_id);

ALTER TABLE ONLY payment_attempts
    ADD CONSTRAINT fk_payment_attempts_intent FOREIGN KEY (merchant_id, payment_intent_id) REFERENCES payment_intents(merchant_id, payment_intent_id);

ALTER TABLE ONLY payment_intents
    ADD CONSTRAINT fk_payment_intents_customer FOREIGN KEY (merchant_id, customer_id) REFERENCES customers(merchant_id, customer_id);

ALTER TABLE ONLY payment_intents
    ADD CONSTRAINT fk_payment_intents_order FOREIGN KEY (merchant_id, order_id) REFERENCES orders(merchant_id, order_id);

ALTER TABLE ONLY payment_method_tokens
    ADD CONSTRAINT fk_payment_method_tokens_customer FOREIGN KEY (merchant_id, customer_id) REFERENCES customers(merchant_id, customer_id);

ALTER TABLE ONLY payment_state_history
    ADD CONSTRAINT fk_payment_state_history_intent FOREIGN KEY (merchant_id, payment_intent_id) REFERENCES payment_intents(merchant_id, payment_intent_id);

ALTER TABLE ONLY provider_callbacks
    ADD CONSTRAINT fk_provider_callbacks_intent FOREIGN KEY (merchant_id, payment_intent_id) REFERENCES payment_intents(merchant_id, payment_intent_id);

ALTER TABLE ONLY refund_callbacks
    ADD CONSTRAINT fk_refund_callbacks_refund FOREIGN KEY (refund_id) REFERENCES refunds(refund_id);

ALTER TABLE ONLY refund_state_history
    ADD CONSTRAINT fk_refund_state_history_refund FOREIGN KEY (refund_id) REFERENCES refunds(refund_id);


