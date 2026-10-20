package com.paymesh.customer.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data access to the customers table.
 * <p>
 * findById(String) is inherited from JpaRepository but is NOT used by the adapter and must not be:
 * it resolves a customer by id alone, with no tenant predicate. Every method declared here names
 * merchantId first, so the generated SQL always carries "where merchant_id = ?".
 */
public interface SpringDataCustomerRepository extends JpaRepository<CustomerJpaEntity, String> {

    Optional<CustomerJpaEntity> findByMerchantIdAndCustomerId(String merchantId, String customerId);

    boolean existsByMerchantIdAndMerchantReference(String merchantId, String merchantReference);
}
