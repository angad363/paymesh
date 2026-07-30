package com.paymesh.customer.infrastructure.persistence.jpa;

import com.paymesh.customer.application.CustomerReferenceAlreadyExistsException;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

/**
 * PostgreSQL-backed implementation of the application's CustomerRepository port.
 * Everything JPA stays on this side of the interface; the services see only domain types.
 */
public final class JpaCustomerRepository implements CustomerRepository {

    private final SpringDataCustomerRepository customers;

    public JpaCustomerRepository(SpringDataCustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public boolean existsByMerchantReference(MerchantId merchantId, String merchantReference) {
        return customers.existsByMerchantIdAndMerchantReference(merchantId.value(), merchantReference);
    }

    @Override
    public Customer save(Customer customer) {
        try {
            // ponytail: saveAndFlush issues a SELECT before the INSERT -- the id is
            // application-assigned and there is no @Version, so Spring Data treats the entity as
            // detached and merges. One extra query per create; implement Persistable#isNew or
            // switch to EntityManager.persist if writes ever get hot.
            CustomerJpaEntity saved = customers.saveAndFlush(CustomerJpaMapper.toEntity(customer));
            return CustomerJpaMapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            // The service's existsByMerchantReference is a check, not a lock: two concurrent creates
            // can both pass it. uq_customers_merchant_reference is the real guard, and the loser of
            // the race must still get a 409 rather than a 500.
            //
            // Narrowed to the case where that constraint can actually fire. With a null reference
            // the unique index cannot be violated, so a violation here is something else entirely
            // (a missing merchant tripping fk_customers_merchant, say) and must not be disguised as
            // a duplicate-reference conflict.
            if(customer.merchantReference() == null) {
                throw exception;
            }

            throw new CustomerReferenceAlreadyExistsException(customer.merchantReference());
        }
    }

    @Override
    public Optional<Customer> findByCustomerId(MerchantId merchantId, CustomerId customerId) {
        return customers.findByMerchantIdAndCustomerId(merchantId.value(), customerId.value())
            .map(CustomerJpaMapper::toDomain);
    }
}
