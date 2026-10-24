package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.SettlementConfig;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A merchant's holding period, or the platform default when they have not set one.
 *
 * <h2>THE DEFAULT IS NOT PERSISTED ON READ</h2>
 *
 * A merchant with no row gets the default as a VALUE, not as a row written on their behalf. Writing
 * one would make "never configured" and "configured to exactly the default" indistinguishable, so a
 * later change to the platform default would silently skip every merchant who had been defaulted
 * into a row -- the opposite of what changing a default means.
 *
 * <h2>EVERY WRITE EMITS {@code settlement.config.updated} (ADR-051)</h2>
 *
 * The ledger service cannot read this schema, so it projects the holding period from the event. The
 * append shares the save's transaction (ADR-010): a rolled-back append rolls the change back too.
 */
public final class GetSettlementConfigService {

    private final SettlementConfigRepository configs;
    private final Duration defaultHoldingPeriod;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public GetSettlementConfigService(
        SettlementConfigRepository configs, Duration defaultHoldingPeriod,
        OutboxWriter outbox, TransactionTemplate transactions, Clock clock
    ) {
        if (defaultHoldingPeriod == null || defaultHoldingPeriod.isNegative()) {
            throw new IllegalArgumentException(
                "Default holding period must be present and non-negative"
            );
        }

        this.configs = configs;
        this.defaultHoldingPeriod = defaultHoldingPeriod;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public SettlementConfig forMerchant(MerchantId merchantId) {
        return configs.find(merchantId).orElseGet(() -> {
            Instant now = Instant.now(clock);

            // NO DESTINATION AND THE SMALLEST MINIMUM. A default payout destination cannot exist --
            // there is no bank account to guess -- so an unconfigured merchant is simply never
            // batched, which is the safe direction to fail in.
            return new SettlementConfig(merchantId, defaultHoldingPeriod, null, 1L, now, now);
        });
    }

    /**
     * Upsert. The merchant is the key, so there is no create-versus-update for a caller to get
     * wrong, and PUT semantics: every setting is replaced rather than merged.
     */
    /**
     * Set only the holding period, leaving the payout destination and minimum at their current
     * values (or the defaults for a merchant with no row). The release path cares about the holding
     * period alone, so this is what it calls; the full setter is the API's.
     */
    public SettlementConfig set(MerchantId merchantId, Duration holdingPeriod) {
        // READ-MERGE-SAVE-EMIT IN ONE TRANSACTION, reading existing INSIDE it. Pre-reading the row to
        // carry its payoutDestination/minimum into the full setter would re-read inside the
        // transaction and overwrite with the stale outer values, silently reverting a concurrent full
        // PUT. Here the holding-period-only merge keeps the other fields from the in-transaction read.
        Instant now = Instant.now(clock);

        return transactions.execute(status -> saveAndEmit(
            configs.find(merchantId)
                .map(existing -> existing.with(
                    holdingPeriod, existing.payoutDestination(), existing.minimumPayoutMinor(), now
                ))
                .orElseGet(() -> new SettlementConfig(merchantId, holdingPeriod, null, 1L, now, now)),
            now
        ));
    }

    public SettlementConfig set(
        MerchantId merchantId,
        Duration holdingPeriod,
        String payoutDestination,
        long minimumPayoutMinor
    ) {
        Instant now = Instant.now(clock);

        return transactions.execute(status -> saveAndEmit(
            configs.find(merchantId)
                .map(existing -> existing.with(
                    holdingPeriod, payoutDestination, minimumPayoutMinor, now
                ))
                .orElseGet(() -> new SettlementConfig(
                    merchantId, holdingPeriod, payoutDestination, minimumPayoutMinor, now, now
                )),
            now
        ));
    }

    /** Save the computed config and emit settlement.config.updated in the same transaction (ADR-051). */
    private SettlementConfig saveAndEmit(SettlementConfig toSave, Instant now) {
        SettlementConfig saved = configs.save(toSave);
        outbox.append(configUpdated(saved, now));
        return saved;
    }

    private static OutboxEvent configUpdated(SettlementConfig config, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", config.merchantId().value());
        payload.put("holdingPeriodSeconds", config.holdingPeriod().toSeconds());
        payload.put("occurredAt", occurredAt.toString());

        return new OutboxEvent(
            EventId.generate(), config.merchantId(), "SETTLEMENT_CONFIG",
            config.merchantId().value(), "settlement.config.updated", 1, payload, occurredAt
        );
    }
}
