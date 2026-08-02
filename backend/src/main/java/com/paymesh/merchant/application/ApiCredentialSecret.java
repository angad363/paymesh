package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.ApiCredential;

/**
 * A newly issued credential and its plaintext secret, together, exactly once.
 * <p>
 * The secret is never stored and never recoverable. This record exists so the create endpoint can
 * return it in the one response where it legitimately appears, and so that nothing else in the
 * codebase has a type that could accidentally carry it further.
 *
 * @param secret the full value the caller must send back, {@code <publicPrefix>.<secret>}
 */
public record ApiCredentialSecret(ApiCredential credential, String secret) {
}
