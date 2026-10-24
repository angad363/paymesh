package com.paymesh.ledger.infrastructure.persistence.jpa;

import com.paymesh.ledger.application.AvailableContribution;
import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

public final class JpaBalanceRepository implements BalanceRepository {

    private final SpringDataLedgerEntryRepository entries;
    private final SpringDataLedgerAccountRepository accounts;

    public JpaBalanceRepository(
        SpringDataLedgerEntryRepository entries, SpringDataLedgerAccountRepository accounts
    ) {
        this.entries = entries;
        this.accounts = accounts;
    }

    @Override
    public List<MerchantBalance> byMerchant(MerchantId merchantId) {
        return entries.balancesByMerchant(merchantId.value());
    }

    @Override
    public long pendingRemainingForPayment(String paymentIntentId) {
        return entries.pendingRemainingForPayment(paymentIntentId);
    }

    @Override
    public List<AvailableContribution> availableContributionsByMerchant(MerchantId merchantId) {
        return entries.availableContributionsByMerchant(merchantId.value()).stream()
            .map(row -> new AvailableContribution(
                (String) row[0], (String) row[1], (Long) row[2]
            ))
            .toList();
    }

    @Override
    public List<MerchantId> merchantsWithAnAvailableAccount() {
        return accounts.merchantsWithAccountType("MERCHANT_AVAILABLE").stream()
            .map(MerchantId::from)
            .toList();
    }
}
