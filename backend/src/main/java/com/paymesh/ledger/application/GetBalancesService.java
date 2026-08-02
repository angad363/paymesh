package com.paymesh.ledger.application;

import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/**
 * Reads one merchant's balances.
 *
 * <h2>A MERCHANT WITH NO PAYMENTS GETS AN EMPTY LIST, NOT A 404</h2>
 *
 * There is no balance resource to be missing. A merchant who has never been paid has a balance --
 * it is nothing, in no currency -- and answering 404 would mean the caller has to treat "you have
 * earned nothing yet" as an error path. The currencies present are the currencies they have been
 * paid in, which is a fact about their history rather than about their account.
 */
public final class GetBalancesService {

    private final BalanceRepository balances;

    public GetBalancesService(BalanceRepository balances) {
        this.balances = balances;
    }

    public List<MerchantBalance> forMerchant(MerchantId merchantId) {
        return balances.pendingBalances(merchantId);
    }
}
