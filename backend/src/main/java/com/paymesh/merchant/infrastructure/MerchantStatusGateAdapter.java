package com.paymesh.merchant.infrastructure;

import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.shared.tenant.MerchantStatusGate;

/**
 * The Merchant module answering the platform's question. See {@link MerchantStatusGate}.
 *
 * <h2>NOT CACHED, AND THAT IS THE POINT</h2>
 *
 * A cache here would mean a suspended merchant kept trading for the cache's lifetime, which is
 * exactly the window an incident is trying to close. This is one indexed primary-key read per
 * authenticated write, on a table with one row per merchant -- the cheapest query in the system.
 * If it ever measures slow, the fix is a cache with explicit invalidation on suspend, not a TTL.
 */
public final class MerchantStatusGateAdapter implements MerchantStatusGate {

    private final MerchantRepository merchants;

    public MerchantStatusGateAdapter(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    public boolean canTransact(MerchantId merchantId) {
        return merchants.findByMerchantId(merchantId)
            .map(merchant -> merchant.canTransact())
            .orElse(false);
    }
}
