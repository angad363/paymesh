package com.paymesh.ledger.infrastructure.persistence.jpa;

import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

public final class JpaBalanceRepository implements BalanceRepository {

    private final SpringDataLedgerEntryRepository entries;

    public JpaBalanceRepository(SpringDataLedgerEntryRepository entries) {
        this.entries = entries;
    }

    @Override
    public List<MerchantBalance> pendingBalances(MerchantId merchantId) {
        return entries.balancesByMerchant(merchantId.value());
    }
}
