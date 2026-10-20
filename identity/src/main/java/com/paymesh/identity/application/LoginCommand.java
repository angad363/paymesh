package com.paymesh.identity.application;

/** @param ipAddress caller address for the audit trail. May be null. */
public record LoginCommand(
    String email,
    String password,
    String ipAddress
) {
}
