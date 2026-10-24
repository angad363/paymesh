package com.paymesh.ledger.api;

import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The narrow ledger read the settlement SERVICE cuts batches from (ADR-047 section 1a). The ledger
 * has no HTTP API of its own until PR 14; this is bolted onto the monolith for one caller and PR 14
 * replaces it. {@code /internal/**}, authenticated by
 * {@link com.paymesh.ledger.infrastructure.security.LedgerReadKeyFilter}'s shared key, never a
 * merchant token -- the merchant id here is the CALLER'S (Settlement's) to name, not a tenant claim.
 */
@RestController
@RequestMapping("internal/v1/ledger")
public final class LedgerReadController {

    private final GetBalancesService balances;

    public LedgerReadController(GetBalancesService balances) {
        this.balances = balances;
    }

    @GetMapping("merchants/{merchantId}/available-contributions")
    ContributionsResponse contributions(@PathVariable String merchantId) {
        return new ContributionsResponse(
            balances.availableContributions(MerchantId.from(merchantId)).stream()
                .map(c -> new Contribution(c.currency(), c.paymentIntentId(), c.amountMinor()))
                .toList()
        );
    }

    /** Settlement's sweep candidates: every merchant who has ever held an available account. */
    @GetMapping("available-merchants")
    AvailableMerchantsResponse availableMerchants() {
        return new AvailableMerchantsResponse(
            balances.merchantsWithAnAvailableAccount().stream().map(MerchantId::value).toList()
        );
    }

    record ContributionsResponse(List<Contribution> contributions) {
    }

    record Contribution(String currency, String paymentIntentId, long amountMinor) {
    }

    record AvailableMerchantsResponse(List<String> merchantIds) {
    }
}
