package com.paymesh.shared.security;

import com.paymesh.shared.tenant.MerchantId;

/**
 * What a verified API key turns out to be.
 *
 * @param subject the credential's own identifier, NOT a user id. An API key is not a person, and
 *     attributing a machine's writes to whoever created the key would put the wrong name in every
 *     audit row it produces.
 * @param role a machine holds the same role vocabulary a human does, so there is one authorization
 *     model rather than two
 */
public record ApiKeyIdentity(String subject, CallerRole role, MerchantId merchantId) {

    public ApiKeyIdentity {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("An API key identity must have a subject");
        }

        if (role == null || merchantId == null) {
            throw new IllegalArgumentException("An API key identity must have a role and a merchant");
        }
    }
}
