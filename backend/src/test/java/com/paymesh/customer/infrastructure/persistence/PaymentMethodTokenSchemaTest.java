package com.paymesh.customer.infrastructure.persistence;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the tenant guarantee on {@code payment_method_tokens} at the database level.
 * <p>
 * Nothing maps this table yet -- no JPA entity, no repository, no code path that writes it -- so
 * V6's composite foreign key has no application code to exercise it. That makes the migration
 * itself the deliverable, and the only honest way to test a constraint with no aggregate above it
 * is to attack it with raw SQL, the way a bad write from any path would.
 * <p>
 * Both directions matter. The cross-tenant insert failing is the point; the same-tenant insert
 * SUCCEEDING is what proves the constraint refuses the right rows rather than all of them -- a
 * mistyped column order would fail both, and only the first test notices.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Transactional
class PaymentMethodTokenSchemaTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-31T10:15:30Z");

    private static final String INSERT = """
        INSERT INTO payment_method_tokens (
            payment_method_token_id, merchant_id, customer_id,
            provider, provider_token, fingerprint, created_at
        ) VALUES (?, ?, ?, 'STRIPE_SIM', ?, ?, ?)
        """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private CustomerRepository customers;

    @Test
    void acceptsATokenNamingACustomerOfItsOwnMerchant() {
        MerchantId merchantId = existingMerchant();
        String customerId = existingCustomer(merchantId);

        assertThat(insertToken(merchantId, customerId)).isEqualTo(1);
    }

    /**
     * THE HOLE V6 CLOSES. Before it, both single-column foreign keys were satisfied -- the merchant
     * exists, the customer exists -- and Postgres accepted a token belonging to one merchant that
     * pointed at another merchant's buyer. The composite key on (merchant_id, customer_id) has no
     * row to match, so the same insert is now refused by the database rather than by a promise.
     */
    @Test
    void refusesATokenNamingAnotherMerchantsCustomer() {
        MerchantId owner = existingMerchant();
        MerchantId outsider = existingMerchant();
        String customerOfOwner = existingCustomer(owner);

        assertThatThrownBy(() -> insertToken(outsider, customerOfOwner))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_payment_method_tokens_customer");
    }

    // --- helpers ---------------------------------------------------------------

    private int insertToken(MerchantId merchantId, String customerId) {
        String unique = UUID.randomUUID().toString();
        return jdbc.update(
            INSERT,
            "pmt_" + UUID.randomUUID(),
            merchantId.value(),
            customerId,
            "tok_" + unique,
            unique.replace("-", "") + unique.replace("-", "").substring(0, 32),
            Timestamp.from(CREATED_AT)
        );
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            Instant.now()
        ).activate(Instant.now())).merchantId();
    }

    private String existingCustomer(MerchantId merchantId) {
        return customers.save(Customer.create(
            CustomerId.generate(),
            merchantId,
            null,
            UUID.randomUUID() + "@buyer.test",
            "Asha Rao",
            null,
            CREATED_AT
        )).customerId().value();
    }
}
