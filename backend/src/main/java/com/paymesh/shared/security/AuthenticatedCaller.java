package com.paymesh.shared.security;

import com.paymesh.shared.tenant.MerchantId;

import java.util.Set;

/**
 * Who is making this request, and which merchants they may act for.
 * <p>
 * Controllers receive this instead of reading claims themselves, so no endpoint can accidentally
 * take a tenant from the request body. The merchants here came out of a signed token that the
 * filter chain already verified; nothing the caller writes in a URL or payload reaches this type.
 *
 * @param userId     the subject of the access token
 * @param merchantIds every merchant the caller holds a role at, possibly none
 */
public record AuthenticatedCaller(String userId, Set<MerchantId> merchantIds) {

    public AuthenticatedCaller {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated caller must have a user id");
        }

        merchantIds = Set.copyOf(merchantIds);
    }

    public boolean canActFor(MerchantId merchantId) {
        return merchantIds.contains(merchantId);
    }

    /**
     * The single merchant this caller is acting for.
     * <p>
     * Merchant-scoped endpoints need exactly one tenant, and a caller with none has no business
     * calling them. A caller holding roles at several merchants is genuine (an accountant serving
     * multiple businesses) but cannot be resolved implicitly -- guessing would eventually write a
     * row under the wrong merchant, so it fails loudly until an explicit selector exists.
     */
    public MerchantId requireSingleMerchant() {
        if (merchantIds.isEmpty()) {
            throw new NoMerchantScopeException(
                "This account is not associated with a merchant."
            );
        }

        if (merchantIds.size() > 1) {
            throw new NoMerchantScopeException(
                "This account is associated with several merchants; the merchant to act for "
                    + "must be stated explicitly."
            );
        }

        return merchantIds.iterator().next();
    }
}
