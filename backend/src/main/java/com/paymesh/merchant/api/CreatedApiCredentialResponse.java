package com.paymesh.merchant.api;

import com.paymesh.merchant.application.ApiCredentialSecret;

/**
 * THE ONLY RESPONSE IN THE PLATFORM THAT CARRIES A SECRET, and it does so exactly once.
 * <p>
 * A separate type from {@link ApiCredentialResponse} on purpose. If the secret were a nullable
 * field on the ordinary response, every read would be one forgotten branch away from returning it.
 * Here, leaking it requires deliberately choosing this class.
 *
 * @param secret the full credential, {@code ak_&lt;prefix&gt;.&lt;secret&gt;}. Never stored, never
 *     logged, never recoverable -- a merchant who loses it issues a new key and revokes this one.
 */
public record CreatedApiCredentialResponse(ApiCredentialResponse credential, String secret) {

    public static CreatedApiCredentialResponse from(ApiCredentialSecret issued) {
        return new CreatedApiCredentialResponse(
            ApiCredentialResponse.from(issued.credential()),
            issued.secret()
        );
    }
}
