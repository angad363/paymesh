package com.paymesh.settlement.api;

import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.settlement.application.SettlementConfigRepository;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * {@code /api/v1/settlement-config} -- the merchant's holding period. SDD 17.4, ADR-031.
 *
 * <h2>PUT RATHER THAN POST, BECAUSE THE MERCHANT IS THE KEY</h2>
 *
 * There is exactly one config per merchant and the caller cannot name which one, so there is no
 * "create" distinct from "update" and nothing for a second PUT to duplicate. That also makes it
 * naturally idempotent without the idempotency filter: sending the same body twice leaves the same
 * single row, which is what {@code Idempotency-Key} would have bought.
 *
 * <h2>The merchant is derived, never accepted</h2>
 *
 * Same rule as {@code BalanceController}: taken from the verified token, with no path variable or
 * body field to tamper with. The request cannot express another tenant's config, so there is no
 * cross-tenant write to defend against and no 403 to return.
 */
@RestController
@RequestMapping("api/v1/settlement-config")
public final class SettlementConfigController {

    private final GetSettlementConfigService settlementConfigs;
    private final SettlementConfigRepository configs;

    public SettlementConfigController(
        GetSettlementConfigService settlementConfigs, SettlementConfigRepository configs
    ) {
        this.settlementConfigs = settlementConfigs;
        this.configs = configs;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    SettlementConfigResponse get(AuthenticatedCaller caller) {
        MerchantId merchantId = caller.requireSingleMerchant();

        // Asked separately so the response can say whether this was chosen or inherited. Reading
        // the default must not write a row -- see GetSettlementConfigService.
        boolean configured = configs.find(merchantId).isPresent();

        return SettlementConfigResponse.from(
            settlementConfigs.forMerchant(merchantId), !configured
        );
    }

    @PutMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    SettlementConfigResponse put(
        AuthenticatedCaller caller, @Valid @RequestBody UpdateSettlementConfigRequest request
    ) {
        MerchantId merchantId = caller.requireSingleMerchant();

        return SettlementConfigResponse.from(
            settlementConfigs.set(
                merchantId,
                Duration.ofSeconds(request.holdingPeriodSeconds()),
                request.payoutDestination(),
                // Null means "leave it at the platform's smallest", not "no minimum". The column is
                // NOT NULL for the same reason: every comparison in the batch job would otherwise
                // be a special case.
                request.minimumPayoutMinor() == null ? 1L : request.minimumPayoutMinor()
            ),
            false
        );
    }
}
