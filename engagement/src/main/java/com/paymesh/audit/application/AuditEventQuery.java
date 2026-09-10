package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditWindow;
import com.paymesh.shared.tenant.MerchantId;

/**
 * The filter behind {@code GET /internal/v1/audit-events}. Every field is optional; an all-null
 * query is "the most recent events across the platform".
 *
 * <p>Not a tenant scope. This is platform-staff tooling, so {@code merchantId} narrows the read to
 * one tenant rather than fencing the caller into their own -- the opposite of a merchant-facing
 * endpoint, where the tenant is derived from the token and never accepted as a parameter.
 *
 * @param merchantId narrow to one tenant, or null for all
 * @param action     narrow to one action string, or null for all
 * @param actorId    narrow to one actor, or null for all
 * @param window     narrow to a time interval, or null for no bound
 * @param limit      the most rows to return, already capped by the caller
 */
public record AuditEventQuery(
    MerchantId merchantId,
    String action,
    String actorId,
    AuditWindow window,
    int limit
) {

    public AuditEventQuery {
        if (limit < 1) {
            throw new IllegalArgumentException("An audit query needs a positive limit");
        }
    }
}
