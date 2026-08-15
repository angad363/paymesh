package com.paymesh.settlement.infrastructure.schedule;

import com.paymesh.settlement.application.CutSettlementBatchesService;
import com.paymesh.settlement.application.SubmitPayoutsService;
import com.paymesh.settlement.application.AvailableFunds;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The timer. Two calls and two log lines, and no rule of its own (CLAUDE.md's rule for every
 * scheduled bean here).
 *
 * <p>Cutting and submitting are separate passes on separate schedules because they fail
 * differently: cutting is database work that either commits or does not, and submitting talks to a
 * provider that can be slow or absent. One method doing both would let a provider outage stop
 * batches being cut, which is the half that does not need the provider at all.
 */
@Component
@ConditionalOnProperty(value = "paymesh.settlement.sweep.enabled", havingValue = "true")
public class SettlementSweeper {

    private static final Logger log = LoggerFactory.getLogger(SettlementSweeper.class);

    private final CutSettlementBatchesService cutBatches;
    private final SubmitPayoutsService submitPayouts;
    private final AvailableFunds availableFunds;

    public SettlementSweeper(
        CutSettlementBatchesService cutBatches,
        SubmitPayoutsService submitPayouts,
        AvailableFunds availableFunds
    ) {
        this.cutBatches = cutBatches;
        this.submitPayouts = submitPayouts;
        this.availableFunds = availableFunds;
    }

    @Scheduled(
        fixedDelayString = "${paymesh.settlement.sweep.interval}",
        initialDelayString = "${paymesh.settlement.sweep.initial-delay}"
    )
    public void cut() {
        List<MerchantId> candidates = availableFunds.merchantsWithAnAvailableAccount();
        int cut = 0;

        for (MerchantId merchantId : candidates) {
            try {
                cut += cutBatches.cutFor(merchantId).size();
            } catch (RuntimeException failure) {
                // ONE MERCHANT'S BAD DATA MUST NOT STOP EVERY OTHER MERCHANT BEING PAID. The service
                // is per-merchant precisely so this boundary can exist here.
                log.warn("Could not cut settlement batches for merchantId={}", merchantId.value(), failure);
            }
        }

        if (cut > 0) {
            log.info("Settlement sweep examined={} merchants cut={} batches", candidates.size(), cut);
        }
    }

    @Scheduled(
        fixedDelayString = "${paymesh.settlement.payouts.interval}",
        initialDelayString = "${paymesh.settlement.payouts.initial-delay}"
    )
    public void submit() {
        SubmitPayoutsService.SubmitResult result = submitPayouts.submit();

        if (result.examined() > 0) {
            log.info(
                "Payout submission examined={} submitted={} refused={} gone={} errored={}",
                result.examined(), result.submitted(), result.refused(), result.gone(),
                result.errored()
            );
        }
    }
}
