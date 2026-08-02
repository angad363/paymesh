package com.paymesh.merchant.application;

/** No such credential, or it belongs to another merchant. One answer for both (ADR-007). */
public final class ApiCredentialNotFoundException extends RuntimeException {

    public ApiCredentialNotFoundException(String apiCredentialId) {
        super("No API credential " + apiCredentialId);
    }
}
