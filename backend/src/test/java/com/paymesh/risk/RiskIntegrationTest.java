package com.paymesh.risk;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.PaymentBlockedByRiskException;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.risk.application.DenylistRepository;
import com.paymesh.risk.domain.DenylistEntry;
import com.paymesh.risk.domain.DenylistEntryId;
import com.paymesh.risk.domain.DenylistedEntity;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Risk against a real PostgreSQL: the confirm path, and the invariants that only the database can
 * actually enforce (V27, V28, ADR-030).
 * <p>
 * Deliberately NOT {@code @Transactional}. Half of what this class asserts is a trigger or a CHECK
 * firing on a real write, and an outer test transaction that rolls back at the end would let some
 * of these pass whether or not they were true. Every test registers its own merchant and scopes its
 * queries to it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class RiskIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-06T10:00:00Z");
    private static final long ORDER_AMOUNT_MINOR = 1999;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private DenylistRepository denylist;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private JdbcTemplate jdbc;

    // --- the confirm path ------------------------------------------------------------------

    /** The ordinary case, and the half that is easy to forget: an ALLOW is recorded too. */
    @Test
    void recordsAnAssessmentForAPaymentItAllows() {
        MerchantId merchantId = existingMerchant();
        PaymentIntent intent = confirmableIntent(merchantId, existingCustomer(merchantId));

        confirmPaymentIntentService.confirm(confirm(merchantId, intent, "device-ok"));

        Map<String, Object> row = assessmentFor(intent);

        assertThat(row).containsEntry("outcome", "ALLOW");
        assertThat(row.get("ruleset_version")).isEqualTo(1);
        assertThat(statusOf(intent)).isEqualTo("PROCESSING");
    }

    /**
     * THE ONE THAT MATTERS. A denylisted customer is refused, and the refusal leaves the intent
     * exactly where it was.
     *
     * <p>{@code REQUIRES_CONFIRMATION}, not {@code FAILED}: a denylist entry is a live opinion an
     * operator can retract -- entries even carry an expiry for it -- so burning the intent for a
     * decision that may be reversed in a minute is the harsher default. The merchant retries after
     * the entry is removed and it works, rather than creating a second intent against an order
     * whose only live slot the first one is still holding.
     */
    @Test
    void refusesAConfirmForADenylistedCustomerAndLeavesTheIntentConfirmable() {
        MerchantId merchantId = existingMerchant();
        String customerId = existingCustomer(merchantId);
        PaymentIntent intent = confirmableIntent(merchantId, customerId);

        denylist.add(DenylistEntry.add(
            merchantId, DenylistedEntity.CUSTOMER, customerId, "charged back twice", CREATED_AT, null
        ));

        assertThatThrownBy(() ->
            confirmPaymentIntentService.confirm(confirm(merchantId, intent, "device-ok"))
        ).isInstanceOf(PaymentBlockedByRiskException.class);

        assertThat(statusOf(intent))
            .as("the intent is untouched, so removing the entry makes it confirmable again")
            .isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(assessmentFor(intent)).containsEntry("outcome", "BLOCK");
    }

    /**
     * The assessment must survive the refusal, and that is not free: the block is thrown from inside
     * the confirm's transaction, so a naive implementation rolls the evidence back with it and
     * leaves a merchant asking why their payment failed with nothing on record.
     *
     * <p><b>This test failed when it was first written, and that was the point of writing it.</b>
     * The assessment was being written inside the confirm's transaction, so the BLOCK rolled the
     * evidence back along with the confirm it refused -- the merchant got a 422 naming an
     * assessment id, and the row that id pointed at did not exist. Nothing in the unit tests could
     * see that, because they have no real transaction to roll back.
     *
     * <p>It survives now because {@code SpringDataRiskAssessmentRepository.save} carries
     * {@code REQUIRES_NEW}: the same shape the idempotency record uses, and for the same reason --
     * commit the record before the thing it is about is allowed to fail.
     */
    @Test
    void keepsTheEvidenceEvenThoughTheConfirmTransactionRolledBack() {
        MerchantId merchantId = existingMerchant();
        String customerId = existingCustomer(merchantId);
        PaymentIntent intent = confirmableIntent(merchantId, customerId);

        denylist.add(DenylistEntry.add(
            merchantId, DenylistedEntity.DEVICE, "stolen-device", null, CREATED_AT, null
        ));

        assertThatThrownBy(() ->
            confirmPaymentIntentService.confirm(confirm(merchantId, intent, "stolen-device"))
        ).isInstanceOf(PaymentBlockedByRiskException.class);

        assertThat(assessmentCountFor(intent))
            .as("a block nobody wrote down is a block nobody can explain")
            .isEqualTo(1L);
        assertThat(attemptCountFor(intent))
            .as("and nothing of the confirm itself survived")
            .isZero();
    }

    /** One merchant's denylist must not refuse another merchant's payment. */
    @Test
    void keepsDenylistsPerMerchant() {
        MerchantId denying = existingMerchant();
        MerchantId other = existingMerchant();
        String customerId = existingCustomer(other);

        denylist.add(DenylistEntry.add(
            denying, DenylistedEntity.CUSTOMER, customerId, "not yours", CREATED_AT, null
        ));

        PaymentIntent intent = confirmableIntent(other, customerId);

        confirmPaymentIntentService.confirm(confirm(other, intent, null));

        assertThat(statusOf(intent)).isEqualTo("PROCESSING");
    }

    // --- velocity, against real rows ---------------------------------------------------------

    /**
     * THE INTENT BEING JUDGED MUST NOT COUNT ITSELF, and nothing but a real query can show that.
     *
     * <p>The velocity count reads {@code payment_intents} for this customer inside the window --
     * and the intent being confirmed was created inside that same window moments earlier. The first
     * version of the query had no exclusion, so every payment scored one higher than it should and
     * every threshold fired a confirm early.
     *
     * <p><b>The unit tests could not see this.</b> {@code EvaluateRiskServiceTest} stubs
     * {@code PaymentVelocityLookup} with a hand-set number, so the real predicate is never executed
     * there. An off-by-one in a velocity feature is exactly the kind of defect a stub hides.
     *
     * <p>A single fresh customer with one intent must therefore score ZERO, not one.
     */
    @Test
    void doesNotCountTheIntentItIsCurrentlyJudging() {
        MerchantId merchantId = existingMerchant();
        PaymentIntent intent = confirmableIntent(merchantId, existingCustomer(merchantId));

        confirmPaymentIntentService.confirm(confirm(merchantId, intent, null));

        assertThat(featureOf(intent, "intentsInWindow"))
            .as("one intent exists for this customer and it is the one being judged")
            .isEqualTo(0);
    }

    /**
     * And the count is real: a customer with prior intents inside the window scores them.
     * <p>
     * Each intent needs its own order — {@code uq_payment_intents_live_per_order} allows only one
     * live intent per order, which is the constraint that makes this test have to build three.
     */
    @Test
    void countsThisCustomersEarlierIntentsInTheWindow() {
        MerchantId merchantId = existingMerchant();
        String customerId = existingCustomer(merchantId);

        confirmableIntent(merchantId, customerId);
        confirmableIntent(merchantId, customerId);
        PaymentIntent third = confirmableIntent(merchantId, customerId);

        confirmPaymentIntentService.confirm(confirm(merchantId, third, null));

        assertThat(featureOf(third, "intentsInWindow"))
            .as("two earlier intents, and not the third one being judged")
            .isEqualTo(2);
    }

    /** One merchant's traffic must not raise another merchant's velocity for the same customer id. */
    @Test
    void countsVelocityPerMerchant() {
        MerchantId busy = existingMerchant();
        MerchantId quiet = existingMerchant();
        String sharedCustomerId = existingCustomer(busy);

        confirmableIntent(busy, sharedCustomerId);
        confirmableIntent(busy, sharedCustomerId);

        String quietCustomer = existingCustomer(quiet);
        PaymentIntent theirs = confirmableIntent(quiet, quietCustomer);

        confirmPaymentIntentService.confirm(confirm(quiet, theirs, null));

        assertThat(featureOf(theirs, "intentsInWindow")).isZero();
    }

    // --- what only the database can prove --------------------------------------------------

    /**
     * APPEND-ONLY, ENFORCED BY V27's TRIGGER RATHER THAN BY THE REPOSITORY.
     * <p>
     * The port has no update and no delete, so this cannot be reached through the application at
     * all -- which is exactly why it is worth a test that bypasses it. The guard has to hold for a
     * psql session and for the next repository someone adds a {@code save()} to.
     */
    @Test
    void refusesToUpdateOrDeleteAnAssessment() {
        MerchantId merchantId = existingMerchant();
        PaymentIntent intent = confirmableIntent(merchantId, existingCustomer(merchantId));

        confirmPaymentIntentService.confirm(confirm(merchantId, intent, null));

        String assessmentId = (String) assessmentFor(intent).get("assessment_id");

        assertThatThrownBy(() -> jdbc.update(
            "update risk_assessments set outcome = 'ALLOW' where assessment_id = ?", assessmentId
        )).hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
            "delete from risk_assessments where assessment_id = ?", assessmentId
        )).hasMessageContaining("append-only");
    }

    /**
     * The shape CHECKs V26's own header wished {@code orders.metadata} had.
     * <p>
     * {@code matched_rules} is read back as a List and {@code features} as a record. A JSON object
     * in the first or an array in the second is a row no mapper can rehydrate -- the exact failure
     * that disabled five sweeps in open item 2. These columns are new, so they get the constraint
     * the old ones cannot have retrofitted without a data migration.
     */
    @Test
    void refusesAnAssessmentWhoseJsonColumnsAreTheWrongShape() {
        MerchantId merchantId = existingMerchant();

        assertThatThrownBy(() -> insertAssessment(merchantId, "{}", "{}"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_risk_assessments_matched_rules_shape");

        assertThatThrownBy(() -> insertAssessment(merchantId, "[]", "[]"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_risk_assessments_features_shape");
    }

    /**
     * A denylist that silently stops denying is the worst failure this table has, and a truncated or
     * double-encoded digest would do exactly that: it simply never matches.
     */
    @Test
    void refusesADenylistValueThatIsNotASha256Digest() {
        MerchantId merchantId = existingMerchant();

        assertThatThrownBy(() -> jdbc.update("""
            insert into denylist_entries
                (entry_id, merchant_id, entity_type, hashed_value, created_at)
            values (?, ?, 'CUSTOMER', 'not-a-digest', ?)
            """, DenylistEntryId.generate().value(), merchantId.value(), Timestamp.from(CREATED_AT)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_denylist_entries_hash_format");
    }

    /** Adding the same entity twice must not leave two rows a single remove would half-clear. */
    @Test
    void refusesADuplicateDenylistEntryForOneEntity() {
        MerchantId merchantId = existingMerchant();
        String customerId = existingCustomer(merchantId);

        denylist.add(DenylistEntry.add(
            merchantId, DenylistedEntity.CUSTOMER, customerId, "first", CREATED_AT, null
        ));

        assertThatThrownBy(() -> denylist.add(DenylistEntry.add(
            merchantId, DenylistedEntity.CUSTOMER, customerId, "second", CREATED_AT, null
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers ---------------------------------------------------------------------------

    private void insertAssessment(MerchantId merchantId, String matchedRules, String features) {
        jdbc.update("""
            insert into risk_assessments
                (assessment_id, merchant_id, payment_intent_id, outcome, matched_rules,
                 ruleset_version, features, decided_at)
            values (?, ?, ?, 'ALLOW', ?::jsonb, 1, ?::jsonb, ?)
            """,
            "rsk_" + UUID.randomUUID(), merchantId.value(), "pi_" + UUID.randomUUID(),
            matchedRules, features, Timestamp.from(CREATED_AT));
    }

    /** One field out of the stored feature snapshot, read as the database actually holds it. */
    private Integer featureOf(PaymentIntent intent, String key) {
        return jdbc.queryForObject(
            "select (features ->> ?)::int from risk_assessments where payment_intent_id = ?",
            Integer.class, key, intent.paymentIntentId().value()
        );
    }

    private Map<String, Object> assessmentFor(PaymentIntent intent) {
        return jdbc.queryForMap(
            "select * from risk_assessments where payment_intent_id = ?",
            intent.paymentIntentId().value()
        );
    }

    private Long assessmentCountFor(PaymentIntent intent) {
        return jdbc.queryForObject(
            "select count(*) from risk_assessments where payment_intent_id = ?",
            Long.class, intent.paymentIntentId().value()
        );
    }

    private Long attemptCountFor(PaymentIntent intent) {
        return jdbc.queryForObject(
            "select count(*) from payment_attempts where payment_intent_id = ?",
            Long.class, intent.paymentIntentId().value()
        );
    }

    private String statusOf(PaymentIntent intent) {
        return jdbc.queryForObject(
            "select status from payment_intents where payment_intent_id = ?",
            String.class, intent.paymentIntentId().value()
        );
    }

    private ConfirmPaymentIntentCommand confirm(
        MerchantId merchantId, PaymentIntent intent, String device
    ) {
        return new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, device
        );
    }

    /** Created and attached, so the only thing left that can refuse it is Risk. */
    private PaymentIntent confirmableIntent(MerchantId merchantId, String customerId) {
        String orderId = existingOrder(merchantId, customerId);

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, orderId, customerId, ORDER_AMOUNT_MINOR, "INR", null, null, Map.of()
        ));

        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );

        return intent;
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();
    }

    private String existingCustomer(MerchantId merchantId) {
        return customers.save(Customer.create(
            CustomerId.generate(),
            merchantId,
            null,
            UUID.randomUUID() + "@buyer.test",
            "Test Buyer",
            null,
            CREATED_AT
        )).customerId().value();
    }

    private String existingOrder(MerchantId merchantId, String customerId) {
        return orders.save(Order.create(
            OrderId.generate(),
            merchantId,
            customerId,
            null,
            ORDER_AMOUNT_MINOR,
            "INR",
            null,
            Map.of(),
            null,
            CREATED_AT
        )).orderId().value();
    }
}
