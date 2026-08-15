package com.paymesh.settlement.infrastructure.ledger;

import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.settlement.application.AvailableFunds;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/**
 * Settlement's {@link AvailableFunds}, answered by the Ledger.
 *
 * <h2>THE ONLY FILE IN SETTLEMENT THAT IMPORTS THE LEDGER, AND THE ARROW NOW POINTS BOTH WAYS</h2>
 *
 * The Ledger already reads Settlement for a holding period ({@code SettlementModuleHoldingPeriod},
 * ADR-031). This is the return arrow, and the pair is a cycle at module level -- which
 * {@code ModuleBoundaryTest} otherwise exists to prevent, and which is worth stating rather than
 * discovering.
 * <p>
 * It is allowed because of what crosses in each direction. Out of the Ledger: a {@code Duration}.
 * Into the Ledger: a read of balances, and nothing else -- <b>Settlement cannot post</b>. Every
 * journal is still written by a Ledger consumer reacting to a committed event (ADR-018 §3), so
 * neither module can move money in the other. Each direction is one adapter file, each is named in
 * {@code ModuleBoundaryTest}, and any second file on either side fails the build.
 * <p>
 * The alternative was moving settlement configuration into Merchant to break the cycle. That trades
 * a narrow, named, tested pair for a merchant module that owns a setting only Settlement reads.
 */
public final class LedgerModuleAvailableFunds implements AvailableFunds {

    private final BalanceRepository balances;

    public LedgerModuleAvailableFunds(BalanceRepository balances) {
        this.balances = balances;
    }

    @Override
    public List<PaymentContribution> contributions(MerchantId merchantId) {
        return balances.availableContributionsByMerchant(merchantId).stream()
            .map(contribution -> new PaymentContribution(
                contribution.currency(), contribution.paymentIntentId(), contribution.amountMinor()
            ))
            .toList();
    }

    @Override
    public List<MerchantId> merchantsWithAnAvailableAccount() {
        return balances.merchantsWithAnAvailableAccount();
    }
}
