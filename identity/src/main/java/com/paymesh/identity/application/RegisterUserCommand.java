package com.paymesh.identity.application;

/**
 * A registered user holds no roles; access is granted later by an admin.
 *
 * @param ipAddress caller address for the audit trail. May be null.
 */
public record RegisterUserCommand(
    String email,
    String password,
    String ipAddress
) {
}
