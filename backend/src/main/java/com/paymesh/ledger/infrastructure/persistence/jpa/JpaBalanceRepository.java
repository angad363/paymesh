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
    public List<MerchantBalance> byMerchant(MerchantId merchantId) {
        return entries.balancesByMerchant(merchantId.value());
    }

    @Override
    public long pendingRemainingForPayment(String paymentIntentId) {
        return entries.pendingRemainingForPayment(paymentIntentId);
    }
}
