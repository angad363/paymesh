package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Instant;

/**
 * SDD 9.3's profile update. The platform had no update endpoint of any kind before this.
 * <p>
 * Only the business name is editable, and {@code UpdateMerchantRequest} explains why each other
 * field is not. A null field means "leave it alone", which is what makes this a PATCH rather than
 * a PUT that silently blanks whatever the caller omitted.
 */
public final class UpdateMerchantService {

    private final MerchantRepository merchants;
    private final GetMerchantService getMerchantService;
    private final Clock clock;

    public UpdateMerchantService(
        MerchantRepository merchants,
        GetMerchantService getMerchantService,
        Clock clock
    ) {
        this.merchants = merchants;
        this.getMerchantService = getMerchantService;
        this.clock = clock;
    }

    public Merchant rename(MerchantId merchantId, String businessName) {
        Merchant merchant = getMerchantService.getById(merchantId);

        if (businessName == null || businessName.isBlank()) {
            return merchant;
        }

        return merchants.save(merchant.rename(businessName, Instant.now(clock)));
    }
}
