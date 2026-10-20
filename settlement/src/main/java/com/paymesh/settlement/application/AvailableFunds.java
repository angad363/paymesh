package com.paymesh.settlement.application;

import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/**
 * What the Ledger says a merchant can be paid, and which payments it came from.
 *
 * <h2>SETTLEMENT'S OWN PORT, IMPLEMENTED BY AN ADAPTER IN SETTLEMENT'S INFRASTRUCTURE</h2>
 *
 * ADR-008's rule exactly: the consumer declares the port, the consumer's infrastructure implements
 * it, and nothing in {@code settlement.api}, {@code .application} or {@code .domain} sees the
 * Ledger. In the service-per-capability end state this is an HTTP call to the Ledger's read API,
 * and only this file changes.
 *
 * <h2>The one thing this deliberately does NOT do</h2>
 *
 * It does not post. Settlement writes rows and an outbox event; the Ledger consumes that event and
 * posts its own journal. Reading the ledger through a port and then writing through it as well
 * would be ADR-018 §3's internal posting API arriving by the back door -- a second way into the
 * financial source of truth with no committed state change behind a posting.
 */
public interface AvailableFunds {

    /**
     * One entry per (currency, payment) with a non-zero contribution to available, including
     * negative ones.
     * <p>
     * The sum per currency IS the available balance, which is what lets the batch job compute a net
     * and a statement from one read. Amounts already committed to an earlier batch are still in
     * here -- Settlement owns {@code settlement_items} and nets them off itself, because the Ledger
     * does not know what a batch is.
     */
    List<PaymentContribution> contributions(MerchantId merchantId);

    /**
     * Every merchant who has ever held an available balance.
     * <p>
     * The candidate list for the batch job, and deliberately not "every merchant with a positive
     * balance": that figure is a SUM over entries and computing it here would do the job's work
     * twice, once to choose a merchant and once to itemise them. Merchants who hold nothing cost
     * one query each and are skipped.
     */
    List<MerchantId> merchantsWithAnAvailableAccount();

    /** @param amountMinor signed; negative is a payment refunded past its own release */
    record PaymentContribution(String currency, String paymentIntentId, long amountMinor) {
    }
}
