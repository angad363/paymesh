package com.paymesh;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.shared.tenant.MerchantRefStore;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Test fixture: a merchant this service can see. The payment service does not own merchants (the
 * merchant service does); what it holds is the event-fed {@code merchant_ref} projection (ADR-039),
 * and that is what the merchant gate reads. Seeding it directly is the equivalent of the
 * register + activate + relay dance the monolith's tests did, minus a merchant module that is not
 * in this deployable.
 */
@Component
public class TestMerchants {

    private final MerchantRefStore store;

    TestMerchants(MerchantRefStore store) {
        this.store = store;
    }

    /** A fresh ACTIVE merchant: the only state that may write (ADR-021). */
    public MerchantId active() {
        return withStatus("ACTIVE");
    }

    public MerchantId withStatus(String status) {
        MerchantId merchantId = MerchantId.generate();
        store.upsert(merchantId, status, Instant.now());
        return merchantId;
    }
}
