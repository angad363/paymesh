package com.paymesh.ledger.infrastructure.events;

import com.paymesh.shared.tenant.MerchantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code settlement_config_ref} projection (ADR-051): both sides in one class, like
 * {@code MerchantRefStore}. {@link #upsert} runs inside the dispatcher's transaction; {@link #find}
 * answers the release job's {@code HoldingPeriodPolicy}. Empty means "never configured" -- the
 * caller supplies the platform default as a value; this table never stores one.
 */
public final class SettlementConfigRefStore {

    private final JdbcTemplate jdbc;

    public SettlementConfigRefStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The {@code updated_at} guard drops an older, out-of-order event so a stale period never wins. */
    public void upsert(MerchantId merchantId, long holdingPeriodSeconds, Instant occurredAt) {
        jdbc.update(
            "INSERT INTO settlement_config_ref (merchant_id, holding_period_seconds, updated_at) "
                + "VALUES (?, ?, ?) "
                + "ON CONFLICT (merchant_id) DO UPDATE SET "
                + "holding_period_seconds = EXCLUDED.holding_period_seconds, "
                + "updated_at = EXCLUDED.updated_at "
                + "WHERE EXCLUDED.updated_at >= settlement_config_ref.updated_at",
            merchantId.value(), holdingPeriodSeconds, Timestamp.from(occurredAt)
        );
    }

    public Optional<Duration> find(MerchantId merchantId) {
        List<Long> seconds = jdbc.queryForList(
            "SELECT holding_period_seconds FROM settlement_config_ref WHERE merchant_id = ?",
            Long.class, merchantId.value()
        );

        return seconds.stream().findFirst().map(Duration::ofSeconds);
    }
}
