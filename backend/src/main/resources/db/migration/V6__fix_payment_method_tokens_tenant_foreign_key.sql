-- ============================================================================
--  V6__fix_payment_method_tokens_tenant_foreign_key.sql
--  Closes a cross-tenant hole in payment_method_tokens, the same one V5 closed
--  for orders.
--
--  THE BUG V3 SHIPPED: fk_payment_method_tokens_customer references
--  customers(customer_id) alone, and fk_payment_method_tokens_merchant
--  references merchants(merchant_id) alone. Each column is valid on its own and
--  NOTHING TIES THE PAIR TOGETHER -- so a row naming merchant A and a customer
--  of merchant B satisfies both constraints and the database accepts it. That
--  row is one merchant holding a payment instrument belonging to another
--  merchant's buyer, which is the worst shape this schema can take.
--
--  It is unexploited today: no JPA entity maps this table and no code path
--  writes it. That is precisely why the fix lands now -- once the attach/detach
--  endpoints exist, the same change needs a data audit first.
--
--  V3 is immutable (already merged), so this fixes forward: drop the
--  single-column customer FK and replace it with a composite one on
--  (merchant_id, customer_id), which can only be satisfied by a customer row
--  that carries THIS token's merchant_id. Postgres requires a unique constraint
--  on the referenced columns, and V5 already added
--  uq_customers_merchant_customer for orders, so nothing new is needed here.
--
--  ON fk_payment_method_tokens_merchant, WHICH STAYS: customer_id is NOT NULL
--  on this table, so the composite FK already proves the merchant exists --
--  every row must match a customers row, and that row's merchant_id is itself
--  foreign-keyed to merchants. The separate merchant FK is therefore redundant,
--  and it is kept anyway. Redundant is not wrong: it costs one index probe on
--  an insert to a table nothing writes yet, it states the tenant column's
--  referential meaning where a reader of the table definition will see it, and
--  it is the one constraint that keeps holding if customer_id ever becomes
--  nullable (a merchant-level instrument). orders keeps both FKs for exactly
--  that reason -- its customer_id IS nullable -- and matching that shape means
--  the two tables read the same way.
-- ============================================================================

ALTER TABLE payment_method_tokens
    DROP CONSTRAINT fk_payment_method_tokens_customer;

-- COMPOSITE on purpose. Referencing customer_id alone lets a token name any
-- customer on the platform, and the application check that would prevent it is
-- advisory -- it can pass, and then the row it checked can change. This is the
-- guarantee; a service-layer check exists to turn the violation into a readable
-- 422 instead of a 500. RESTRICT (the default) as before: detaching a payment
-- method is an explicit action, never a side effect of deleting a customer.
ALTER TABLE payment_method_tokens
    ADD CONSTRAINT fk_payment_method_tokens_customer FOREIGN KEY (merchant_id, customer_id)
        REFERENCES customers (merchant_id, customer_id);
