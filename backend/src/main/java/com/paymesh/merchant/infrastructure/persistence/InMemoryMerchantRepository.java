package com.paymesh.merchant.infrastructure.persistence;

import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;

import java.util.ArrayList;
import java.util.List;

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

    int size() {
        return merchants.size();
    }
}
