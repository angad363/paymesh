package com.paymesh.identity.application;

/**
 * @param merchantId optional. When present the new user is granted MERCHANT_ADMIN
 *                   scoped to that merchant; when absent the user holds no roles
 *                   yet. Full membership management belongs to the Merchant
 *                   Service (SDD 9.1) and arrives with merchant.user.invited.
 * @param ipAddress  caller address for the audit trail. May be null.
 */
public record RegisterUserCommand(
    String email,
    String password,
    String merchantId,
    String ipAddress
) {
}
