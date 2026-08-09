package com.paymesh.risk.domain;

/**
 * What kind of thing a denylist entry names.
 * <p>
 * Every constant here is reachable: each is matched by {@code EvaluateRiskService} against a
 * feature that exists today. Adding a constant means adding the match with it -- this codebase has
 * spent three ADRs making unreachable enum values reachable and should not mint new ones.
 */
public enum DenylistedEntity {

    /** A specific customer of a specific merchant. The narrowest and most common entry. */
    CUSTOMER,

    /**
     * The opaque client hint sent on confirm. Matched literally: PayMesh does not parse it, so
     * whatever the merchant uses as a device signature is what gets denied.
     */
    DEVICE
}
