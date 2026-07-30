package com.paymesh.identity.application;

import com.paymesh.identity.domain.UserId;

import java.time.Instant;
import java.util.List;

/**
 * What a verified access token asserts.
 *
 * <p>{@code scopedRoles} entries are {@code "<ROLE>:<merchantId>"}, e.g.
 * {@code "MERCHANT_ADMIN:mrc_..."}. A user can hold roles at several merchants,
 * so the scope travels with each role rather than as one merchant claim.
 *
 * <p>These claims narrow what a caller may attempt. They never replace the
 * merchant_id filter a repository applies (SDD 8.6).
 */
public record AccessTokenClaims(
    UserId userId,
    String email,
    List<String> scopedRoles,
    Instant issuedAt,
    Instant expiresAt
) {
}
