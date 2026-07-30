package com.paymesh.merchant.infrastructure.persistence.jpa;

import com.paymesh.merchant.application.MerchantEmailAlreadyExistsException;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

/**
 * PostgreSQL-backed implementation of the application's MerchantRepository port.
 * Everything JPA stays on this side of the interface; the services see only domain types.
 */
public final class JpaMerchantRepository implements MerchantRepository {

    private final SpringDataMerchantRepository merchants;

    public JpaMerchantRepository(SpringDataMerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    public boolean existsByEmail(String normalizedEmail) {
        return merchants.existsByEmail(normalizedEmail);
    }

    @Override
    public Merchant save(Merchant merchant) {
        try {
            // ponytail: saveAndFlush issues a SELECT before the INSERT -- the id is
            // application-assigned and there is no @Version, so Spring Data treats the entity as
            // detached and merges. One extra query per registration; implement Persistable#isNew
            // or switch to EntityManager.persist if writes ever get hot.
            MerchantJpaEntity saved = merchants.saveAndFlush(MerchantJpaMapper.toEntity(merchant));
            return MerchantJpaMapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            // The service's existsByEmail is a check, not a lock: two concurrent registrations can
            // both pass it. uq_merchants_email is the real guard, and it is the only unique
            // constraint on the table besides the primary key, so a violation here means the email
            // was taken between the check and the insert. Translate it to the same 409.
            throw new MerchantEmailAlreadyExistsException(merchant.email());
        }
    }

    @Override
    public Optional<Merchant> findByMerchantId(MerchantId merchantId) {
        return merchants.findById(merchantId.value())
            .map(MerchantJpaMapper::toDomain);
    }
}
