package com.paymesh.risk.infrastructure.persistence.jpa;

import com.paymesh.risk.application.DenylistRepository;
import com.paymesh.risk.domain.DenylistEntry;
import com.paymesh.risk.domain.DenylistedEntity;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed DenylistRepository. */
public final class JpaDenylistRepository implements DenylistRepository {

    private final SpringDataDenylistRepository entries;

    public JpaDenylistRepository(SpringDataDenylistRepository entries) {
        this.entries = entries;
    }

    @Override
    public DenylistEntry add(DenylistEntry entry) {
        entries.save(RiskJpaMapper.toEntity(entry));
        return entry;
    }

    @Override
    public boolean matchesAny(MerchantId merchantId, List<String> hashedValues, Instant now) {
        if (hashedValues == null || hashedValues.isEmpty()) {
            // An empty IN list is a SQL error on some drivers and an always-false predicate on
            // others. Answering here means the caller never has to know which.
            return false;
        }

        return entries.countLiveMatches(merchantId.value(), hashedValues, now) > 0;
    }

    @Override
    public Optional<DenylistEntry> find(MerchantId merchantId, String entryId) {
        return entries.findByMerchantIdAndEntryId(merchantId.value(), entryId)
            .map(RiskJpaMapper::toDomain);
    }

    @Override
    public List<DenylistEntry> findByType(
        MerchantId merchantId, DenylistedEntity entityType, int limit
    ) {
        return entries
            .findByMerchantIdAndEntityTypeOrderByCreatedAtDesc(
                merchantId.value(), entityType.name(), Limit.of(limit)
            )
            .stream()
            .map(RiskJpaMapper::toDomain)
            .toList();
    }

    @Override
    public boolean remove(MerchantId merchantId, String entryId) {
        return entries.deleteByMerchantIdAndEntryId(merchantId.value(), entryId) > 0;
    }
}
