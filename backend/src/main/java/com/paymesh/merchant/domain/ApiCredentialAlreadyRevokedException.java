package com.paymesh.merchant.domain;

/** Revoking a credential that is already revoked. */
public final class ApiCredentialAlreadyRevokedException extends IllegalStateException {

    public ApiCredentialAlreadyRevokedException(ApiCredentialId apiCredentialId) {
        super("API credential " + apiCredentialId.value() + " is already revoked");
    }
}
