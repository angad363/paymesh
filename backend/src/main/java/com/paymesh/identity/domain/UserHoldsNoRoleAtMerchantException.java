package com.paymesh.identity.domain;

/**
 * This user holds no role at that merchant.
 * <p>
 * Mapped to 404 rather than 409, and deliberately the same answer as "no such user": a merchant
 * admin who could tell the two apart could enumerate every user id on the platform by watching
 * which ones answered differently.
 */
public final class UserHoldsNoRoleAtMerchantException extends RuntimeException {

    public UserHoldsNoRoleAtMerchantException(UserId userId, String merchantId) {
        super("User " + userId.value() + " holds no role at merchant " + merchantId);
    }
}
