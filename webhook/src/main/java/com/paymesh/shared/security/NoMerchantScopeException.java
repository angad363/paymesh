package com.paymesh.shared.security;

/**
 * The caller is authenticated but cannot be resolved to exactly one merchant, so a
 * merchant-scoped endpoint has nothing to scope to. Authentication succeeded; authorization has
 * no tenant to work with -- which is a 403, not a 401.
 */
public class NoMerchantScopeException extends RuntimeException {
    public NoMerchantScopeException(String message) {
        super(message);
    }
}
