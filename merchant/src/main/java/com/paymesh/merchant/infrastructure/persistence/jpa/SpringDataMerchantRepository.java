package com.paymesh.merchant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data access to the merchants table.
 * findById(String) is inherited from JpaRepository, so it is not redeclared here.
 */
public interface SpringDataMerchantRepository extends JpaRepository<MerchantJpaEntity, String> {

    Optional<MerchantJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
