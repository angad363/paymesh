package com.paymesh.settlement.api;

import com.paymesh.settlement.application.GetSettlementsService;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/settlements} -- a merchant's statements. SDD 17.3.
 *
 * <h2>READ ONLY, AND THERE IS NO "RUN A BATCH" ROUTE</h2>
 *
 * The plan listed one for ops. It is not built: cutting a batch is a decision about a balance at an
 * instant, and an endpoint lets two callers make that decision concurrently against the same
 * available funds. The job is the single writer, {@code CutSettlementBatchesService} is an ordinary
 * bean, and a test calls it directly -- which is what the endpoint would have been for.
 *
 * <h2>The tenant is derived, never accepted</h2>
 *
 * From the verified token, like {@code BalanceController}. Another merchant's settlement id
 * therefore answers 404 rather than 403, because saying "forbidden" confirms the id exists.
 */
@RestController
@RequestMapping("api/v1/settlements")
public final class SettlementController {

    private final GetSettlementsService settlements;

    public SettlementController(GetSettlementsService settlements) {
        this.settlements = settlements;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    SettlementBatchListResponse list(AuthenticatedCaller caller) {
        MerchantId merchantId = caller.requireSingleMerchant();

        return new SettlementBatchListResponse(
            settlements.list(merchantId).stream().map(SettlementBatchResponse::from).toList()
        );
    }

    @GetMapping(path = "/{settlementBatchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    SettlementBatchResponse get(AuthenticatedCaller caller, @PathVariable String settlementBatchId) {
        MerchantId merchantId = caller.requireSingleMerchant();

        return SettlementBatchResponse.from(
            settlements.get(merchantId, SettlementBatchId.from(settlementBatchId))
        );
    }
}
