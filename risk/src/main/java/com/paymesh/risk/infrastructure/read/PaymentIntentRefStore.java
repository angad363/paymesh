package com.paymesh.risk.infrastructure.read;

import com.paymesh.risk.application.PaymentVelocityLookup;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The {@code payment_intent_ref} read model: Risk's own copy of the one fact it needs about a
 * payment intent (merchant, customer, when it was created) so the velocity feature can be
 * answered without calling Payment (ADR-044 section 4). Fed by {@link PaymentCreatedProjector} on
 * every {@code payment.created} event, and read on every confirm through the
 * {@link PaymentVelocityLookup} this same class implements.
 *
 * <h2>WHY RAW SQL AND NOT A JPA ENTITY</h2>
 *
 * The write is an idempotent upsert ({@code ON CONFLICT DO NOTHING}) and the read is a single
 * indexed count -- a mapper and a repository interface would be more code for exactly the two
 * queries this class already is. Same instinct {@code MerchantRefStore} already applied to a
 * schema-local table.
 *
 * <h2>WHY THIS IS ONE SCHEMA, UNLIKE {@code MerchantRefStore}</h2>
 *
 * {@code merchant_ref} is copied into six schemas because one in-process projector fed all of
 * them before each service had its own consumer group. {@code payment_intent_ref} was never a
 * monolith table at all -- it is new in this PR, born already schema-local to {@code risk}, so
 * there is exactly one copy to feed and nothing to collapse later.
 */
public final class PaymentIntentRefStore implements PaymentVelocityLookup {

    private final JdbcTemplate jdbc;

    public PaymentIntentRefStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one intent. {@code ON CONFLICT (payment_intent_id) DO NOTHING}: the SAME event
     * redelivered (the inbox already dedupes it, but a handler must be idempotent anyway per
     * {@code EventHandler}'s own contract) or a genuinely duplicate projection attempt both land as
     * a safe no-op rather than a constraint violation the caller has to catch.
     */
    public void record(
        MerchantId merchantId, String customerId, String paymentIntentId, Instant createdAt
    ) {
        jdbc.update(
            "INSERT INTO payment_intent_ref "
                + "(payment_intent_id, merchant_id, customer_id, created_at) "
                + "VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (payment_intent_id) DO NOTHING",
            paymentIntentId, merchantId.value(), customerId, Timestamp.from(createdAt)
        );
    }

    /**
     * THE EVENTUAL-CONSISTENCY CAVEAT, STATED RATHER THAN HIDDEN (ADR-044 section 4).
     *
     * <p>This read model is fed by Kafka, which lags the confirm it is being asked to judge by
     * however long the relay and the broker take to deliver {@code payment.created}. A customer who
     * opens several checkouts within a few hundred milliseconds of each other can therefore be
     * undercounted -- the earlier intents' projector rows may not have landed yet when this query
     * runs. In-process, the same count read Postgres synchronously inside the same transaction and
     * saw every prior row immediately.
     *
     * <p>ponytail: accepted as a heuristic's ceiling, not fixed here. A velocity feature already
     * tolerates noise (SDD 14.6 calls it a signal, not a certainty), and the alternative -- a
     * synchronous cross-service call back into Payment for a fraud heuristic -- reintroduces
     * exactly the coupling this extraction exists to remove. Revisit if a burst-fraud pattern is
     * ever shown to rely on sub-second ordering this lag would miss.
     *
     * @param excludingIntentId the intent being judged; excluded for the same off-by-one reason the
     *     in-process query excludes it (see {@link PaymentVelocityLookup#intentsCreatedSince}).
     */
    @Override
    public int intentsCreatedSince(
        MerchantId merchantId, String customerId, Instant since, String excludingIntentId
    ) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM payment_intent_ref "
                + "WHERE merchant_id = ? AND customer_id = ? AND created_at >= ? "
                + "AND payment_intent_id <> ?",
            Integer.class, merchantId.value(), customerId, Timestamp.from(since), excludingIntentId
        );

        return count == null ? 0 : count;
    }
}
