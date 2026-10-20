package com.paymesh.order.application;

/**
 * One candidate for the expiry sweep: the two identifiers needed to lock it, <b>unparsed</b>.
 *
 * <h2>WHY THIS EXISTS RATHER THAN THE SWEEP READING {@code List&lt;Order&gt;}</h2>
 *
 * The sweep re-reads every candidate under a lock before touching it, so the aggregate the
 * candidate query used to build was thrown away unread. It was not free: mapping happened
 * <b>outside</b> the per-item try/catch, so a single row the mapper could not rehydrate threw out
 * of {@code sweep()} entirely. That row has the oldest deadline, sits at the head of every
 * subsequent batch, and the scheduler keeps rescheduling it -- one bad row silently disabling order
 * expiry platform-wide. Open item 2.
 *
 * <h2>AND WHY THE FIELDS ARE {@code String}, WHICH LOOKS WRONG AND IS THE POINT</h2>
 *
 * {@code MerchantId.from} and {@code OrderId.from} <b>validate</b>: they throw on anything that is
 * not {@code prefix_uuid}. Building them here would put a throwing call right back outside the
 * boundary this record exists to move work inside of -- the first version of this fix did exactly
 * that, and it was the version that still failed the regression test.
 * <p>
 * That is not hypothetical. {@code merchants.merchant_id} and {@code orders.order_id} are
 * {@code VARCHAR(40)} with <b>no format CHECK</b>, so a malformed identifier is a row PostgreSQL
 * accepts today. Open item 2 was filed as latent, "needing database state the current CHECKs
 * forbid". It is not latent; it needs one id nobody constrained.
 * <p>
 * So these stay raw, {@code ExpireOrdersService} parses them inside its {@code try}, and a
 * malformed id costs one order instead of every order on the platform.
 */
public record ExpirableOrder(String merchantId, String orderId) {
}
