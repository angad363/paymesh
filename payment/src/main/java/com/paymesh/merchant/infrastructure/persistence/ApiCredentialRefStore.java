package com.paymesh.merchant.infrastructure.persistence;

import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.domain.ApiCredential;
import com.paymesh.merchant.domain.ApiCredentialId;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code api_credential_ref} projection, both sides, the {@code MerchantRefStore} shape (ADR-039):
 * {@link #upsert} is what the projector writes, and the {@link ApiCredentialRepository} reads are what
 * the ApiKey authenticator verifies against. Raw SQL through {@link JdbcTemplate}, no JPA entity.
 */
public final class ApiCredentialRefStore implements ApiCredentialRepository {

    private final JdbcTemplate jdbc;

    public ApiCredentialRefStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent AND order-tolerant. Everything but {@code revoked_at} is immutable for a credential,
     * and revocation only ever moves from null to a value: {@code COALESCE(existing, incoming)} means
     * a redelivered {@code issued} cannot un-revoke a key, and a {@code revoked} that overtakes its
     * {@code issued} still lands (the row is created already revoked).
     */
    public void upsert(
        String apiCredentialId,
        String merchantId,
        String publicPrefix,
        String secretHash,
        String role,
        Instant revokedAt
    ) {
        jdbc.update(
            "INSERT INTO api_credential_ref "
                + "(api_credential_id, merchant_id, public_prefix, secret_hash, role, revoked_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (api_credential_id) DO UPDATE SET "
                + "revoked_at = COALESCE(api_credential_ref.revoked_at, EXCLUDED.revoked_at)",
            apiCredentialId, merchantId, publicPrefix, secretHash, role,
            revokedAt == null ? null : Timestamp.from(revokedAt)
        );
    }

    @Override
    public Optional<ApiCredential> findByPublicPrefix(String publicPrefix) {
        List<ApiCredential> found = jdbc.query(
            "SELECT api_credential_id, merchant_id, public_prefix, secret_hash, role, revoked_at "
                + "FROM api_credential_ref WHERE public_prefix = ?",
            (rs, row) -> new ApiCredential(
                ApiCredentialId.from(rs.getString("api_credential_id")),
                MerchantId.from(rs.getString("merchant_id")),
                rs.getString("public_prefix"),
                rs.getString("secret_hash"),
                CallerRole.valueOf(rs.getString("role")),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                null
            ),
            publicPrefix
        );

        return found.stream().findFirst();
    }

    @Override
    public void touchLastUsed(ApiCredentialId apiCredentialId, Instant usedAt) {
        // Deliberately nothing: see the interface.
    }
}
