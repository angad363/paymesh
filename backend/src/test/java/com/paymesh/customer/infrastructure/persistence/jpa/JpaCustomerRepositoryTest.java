package com.paymesh.customer.infrastructure.persistence.jpa;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CustomerReferenceAlreadyExistsException;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class JpaCustomerRepositoryTest {

    @Autowired
    private CustomerRepository repository;

    /**
     * customers.merchant_id is foreign-keyed to merchants, so a tenant has to exist before any of
     * its customers can. That constraint is part of what is under test here.
     */
    @Autowired
    private MerchantRepository merchants;

    @Test
    void savesAndReadsBackACustomer() {
        MerchantId merchantId = existingMerchant();
        Customer customer = repository.save(customer(merchantId, "user-42"));

        Customer found = repository.findByCustomerId(merchantId, customer.customerId()).orElseThrow();

        assertThat(found).usingRecursiveComparison().isEqualTo(customer);
    }

    @Test
    void returnsEmptyWhenCustomerIdIsUnknown() {
        assertThat(repository.findByCustomerId(existingMerchant(), CustomerId.generate())).isEmpty();
    }

    /**
     * The isolation invariant, proved against the real query: merchant B holds a valid customer id
     * belonging to merchant A and still gets nothing back. If the adapter ever fell back to
     * findById(customerId) this is the test that fails.
     */
    @Test
    void doesNotReturnACustomerOwnedByAnotherMerchant() {
        MerchantId merchantA = existingMerchant();
        MerchantId merchantB = existingMerchant();
        Customer customerOfA = repository.save(customer(merchantA, "user-42"));

        assertThat(repository.findByCustomerId(merchantB, customerOfA.customerId())).isEmpty();
    }

    @Test
    void scopesTheMerchantReferenceExistenceCheckToTheMerchant() {
        MerchantId merchantA = existingMerchant();
        MerchantId merchantB = existingMerchant();
        repository.save(customer(merchantA, "user-42"));

        assertThat(repository.existsByMerchantReference(merchantA, "user-42")).isTrue();
        assertThat(repository.existsByMerchantReference(merchantB, "user-42")).isFalse();
    }

    /**
     * merchant_reference is unique WITHIN a merchant, so the same reference under two different
     * merchants is two valid rows, not a conflict.
     */
    @Test
    void allowsTheSameMerchantReferenceUnderDifferentMerchants() {
        Customer first = repository.save(customer(existingMerchant(), "user-42"));
        Customer second = repository.save(customer(existingMerchant(), "user-42"));

        assertThat(first.customerId()).isNotEqualTo(second.customerId());
        assertThat(second.merchantReference()).isEqualTo("user-42");
    }

    /**
     * NULLs are distinct under a Postgres unique index, which is exactly why the optional reference
     * is stored as NULL rather than an empty string: any number of customers may have none.
     */
    @Test
    void allowsManyCustomersWithoutAMerchantReference() {
        MerchantId merchantId = existingMerchant();

        repository.save(customer(merchantId, null));
        Customer second = repository.save(customer(merchantId, null));

        assertThat(repository.findByCustomerId(merchantId, second.customerId())).isPresent();
    }

    /**
     * Simulates the create race: the service's existsByMerchantReference check passes, then someone
     * else inserts the same reference before the write lands. The unique constraint must surface as
     * the business exception, not as a raw DataIntegrityViolationException (which would be a 500).
     */
    @Test
    void translatesTheUniqueMerchantReferenceConstraintIntoABusinessException() {
        MerchantId merchantId = existingMerchant();
        repository.save(customer(merchantId, "user-42"));

        assertThatThrownBy(() -> repository.save(customer(merchantId, "user-42")))
            .isInstanceOf(CustomerReferenceAlreadyExistsException.class)
            .hasMessage("A customer already exists with merchant reference user-42");
    }

    private MerchantId existingMerchant() {
        Merchant merchant = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            Instant.now()
        ));

        return merchant.merchantId();
    }

    private static Customer customer(MerchantId merchantId, String merchantReference) {
        return Customer.create(
            CustomerId.generate(),
            merchantId,
            merchantReference,
            "buyer@example.com",
            "Asha Rao",
            "+919876543210",
            Instant.parse("2026-07-19T10:15:30Z")
        );
    }
}
