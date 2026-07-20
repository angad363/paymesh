package com.paymesh.merchant.infrastructure.persistence;

import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class InMemoryMerchantRepository implements MerchantRepository {

    private final List<Merchant> merchants = new ArrayList<>();

    @Override
    public boolean existsByEmail(String normalizedEmail) {
        return merchants.stream()
            .anyMatch(merchant ->
                merchant.email().equals(normalizedEmail));
    }

    @Override
    public Merchant save(Merchant merchant) {
        if (merchant == null) {
            throw new IllegalArgumentException(
                "Merchant cannot be null."
            );
        }
        merchants.add(merchant);
        return merchant;
    }

    @Override
    public Optional<Merchant> findByMerchantId(MerchantId merchantId) {
        return merchants.stream()
            .filter(merchant ->
                merchant.merchantId().equals(merchantId)
            )
            .findFirst();
    }

    int size() {
        return merchants.size();
    }
}
