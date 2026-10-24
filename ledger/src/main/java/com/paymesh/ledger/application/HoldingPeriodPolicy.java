package com.paymesh.ledger.application;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Duration;

/**
 * How long this merchant's captured funds stay pending, defined by the Ledger (ADR-008).
 *
 * <h2>THIS ARROW POINTS OUT OF THE LEDGER, AND THAT IS THE DIRECTION THAT IS ALLOWED</h2>
 *
 * {@code ModuleBoundaryTest} seals the Ledger in one direction absolutely: <b>nothing may reach IN</b>
 * to read a balance or post an entry, because that is what keeps every posting traceable to a state
 * change (ADR-018 §6). A policy value flowing the other way breaks none of that -- the release job
 * still posts its own journal, from inside the Ledger, in response to time passing rather than to
 * an outside caller.
 * <p>
 * It is still a boundary, so it gets the treatment every other one here gets: an interface the
 * consumer owns, exactly one adapter, and a test that allows that one file and nothing else.
 */
public interface HoldingPeriodPolicy {

    Duration forMerchant(MerchantId merchantId);
}
