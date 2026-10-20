package com.paymesh.merchant.infrastructure.persistence.jpa;

import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.merchant.domain.ApiCredentialId;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JpaApiCredentialRepository implements ApiCredentialRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaApiCredentialRepository.class);

    private final SpringDataApiCredentialRepository credentials;

    public JpaApiCredentialRepository(SpringDataApiCredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Override
    public ApiCredential save(ApiCredential credential) {
        return toDomain(credentials.saveAndFlush(toEntity(credential)));
    }

    @Override
    public Optional<ApiCredential> findByPublicPrefix(String publicPrefix) {
        return credentials.findByPublicPrefix(publicPrefix).map(JpaApiCredentialRepository::toDomain);
    }

    @Override
    public Optional<ApiCredential> findById(MerchantId merchantId, ApiCredentialId apiCredentialId) {
        return credentials
            .findByMerchantIdAndApiCredentialId(merchantId.value(), apiCredentialId.value())
            .map(JpaApiCredentialRepository::toDomain);
    }

    @Override
    public List<ApiCredential> findByMerchant(MerchantId merchantId) {
        return credentials.findByMerchantIdOrderByCreatedAtDesc(merchantId.value())
            .stream()
            .map(JpaApiCredentialRepository::toDomain)
            .toList();
    }

    /**
     * BEST EFFORT, IN ITS OWN TRANSACTION, AND FAILURE IS SWALLOWED.
     * <p>
     * The Spring Data method carries {@code REQUIRES_NEW} so this cannot enlist in -- or poison --
     * whatever transaction the request goes on to open; it is declared on the interface rather than
     * here because this class is final and Spring cannot proxy a final class. The catch is
     * deliberate: {@code last_used_at} is an operational
     * convenience for spotting unrotated keys. If writing it fails, the correct outcome is a
     * slightly stale timestamp, not a rejected payment. Letting it throw would make an
     * observability column able to take the platform down.
     */
    @Override
    public void touchLastUsed(ApiCredentialId apiCredentialId, Instant usedAt) {
        try {
            credentials.touchLastUsed(apiCredentialId.value(), usedAt);
        } catch (RuntimeException exception) {
            log.debug(
                "Could not record API credential use apiCredentialId={}: {}",
                apiCredentialId.value(), exception.getMessage()
            );
        }
    }

    private static ApiCredentialJpaEntity toEntity(ApiCredential credential) {
        return new ApiCredentialJpaEntity(
            credential.apiCredentialId().value(),
            credential.merchantId().value(),
            credential.publicPrefix(),
            credential.secretHash(),
            credential.role().name(),
            credential.label(),
            credential.revokedAt(),
            credential.lastUsedAt(),
            credential.createdAt()
        );
    }

    private static ApiCredential toDomain(ApiCredentialJpaEntity entity) {
        return new ApiCredential(
            ApiCredentialId.from(entity.apiCredentialId()),
            MerchantId.from(entity.merchantId()),
            entity.publicPrefix(),
            entity.secretHash(),
            CallerRole.valueOf(entity.role()),
            entity.label(),
            entity.revokedAt(),
            entity.lastUsedAt(),
            entity.createdAt()
        );
    }
}
