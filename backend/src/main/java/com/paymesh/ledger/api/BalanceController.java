package com.paymesh.ledger.api;

import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/balances} -- SDD 15.3's merchant-facing balance read.
 *
 * <h2>THE PATH IS /api/v1, NOT SDD 15.3's /v1</h2>
 *
 * The SDD writes {@code GET /v1/balances}. Every route this codebase actually serves is under
 * {@code /api/v1}, so this follows the code -- CLAUDE.md's rule for exactly this case: match the
 * existing code when the docs and the code disagree, and say so rather than silently picking one.
 *
 * <h2>The merchant is derived, never accepted</h2>
 *
 * {@code caller.requireSingleMerchant()} takes it from the verified token. There is no
 * {@code merchantId} path variable, query parameter or body field to tamper with, so there is no
 * cross-tenant read to defend against -- the request cannot express one. This is why the endpoint
 * needs no ownership check and returns no 403: the only balance it can address is the caller's.
 *
 * <h2>No security configuration change</h2>
 *
 * {@code SecurityConfiguration} authenticates everything under {@code /api/v1} that is not
 * explicitly permitted, and this route is not on that list. Adding a rule for it would be a
 * no-op that implies the default is the other way round.
 */
@RestController
@RequestMapping("api/v1/balances")
public final class BalanceController {

    private final GetBalancesService getBalancesService;

    public BalanceController(GetBalancesService getBalancesService) {
        this.getBalancesService = getBalancesService;
    }

    @GetMapping
    BalanceListResponse list(AuthenticatedCaller caller) {
        MerchantId merchantId = caller.requireSingleMerchant();

        return BalanceListResponse.from(getBalancesService.forMerchant(merchantId));
    }
}
