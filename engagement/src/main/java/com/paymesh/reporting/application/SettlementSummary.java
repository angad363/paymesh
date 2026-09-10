package com.paymesh.reporting.application;

/**
 * SDD 19.2's "settlement summaries", for ONE currency.
 *
 * <h2>AN AGGREGATE, WHICH IS WHAT MAKES IT DIFFERENT FROM GET /api/v1/settlements</h2>
 *
 * Settlement already lists a merchant's batches with their statuses, and duplicating that list here
 * would be a second read of the same rows with a worse guarantee (this side is eventually
 * consistent). What this answers is the question the list does not: over a window, how much was cut,
 * how much actually landed, and how much came back.
 *
 * <p>The three counts come from three events and do not partition: a batch that is cut and then
 * paid appears in {@code batchesCut} and in {@code batchesPaid}. That is the point -- the gap
 * between them is money in flight.
 */
public record SettlementSummary(
    String currency,
    long batchesCut,
    long cutAmountMinor,
    long batchesPaid,
    long paidAmountMinor,
    long batchesReturned,
    long returnedAmountMinor
) {

    // NO DERIVED "in flight" FIELD, DELIBERATELY. cut minus paid minus returned looks like money in
    // transit, but over a WINDOW it is not: a batch cut before the window and paid inside it makes
    // the subtraction negative, because the three counts come from three events that do not
    // partition. And the true "how much is in transit right now" is a STOCK the Ledger already
    // holds as its SETTLEMENT_IN_TRANSIT balance (ADR-032) -- a windowed fact table is the wrong
    // place to recompute it. The raw cut/paid/returned totals are always correct; the balance is
    // the Ledger's to report.
}
