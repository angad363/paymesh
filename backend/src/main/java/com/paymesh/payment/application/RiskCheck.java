package com.paymesh.payment.application;

import com.paymesh.shared.tenant.MerchantId;

/**
 * What Payment needs from Risk, defined by Payment (ADR-008, the same shape as {@link OrderLookup}).
 *
 * <h2>THE ANSWER IS A BOOLEAN AND AN ID, AND THE MISSING FIELD IS DELIBERATE</h2>
 *
 * It does not return the rules that matched. Risk records them -- that is the whole point of
 * {@code risk_assessments} -- but handing them back to the caller puts them one hop from the error
 * body, and an error body that says WHICH rule refused a payment is a free oracle: retry, vary one
 * input, watch which message changes, and the ruleset is mapped. The same reasoning
 * {@code OrderNotPayableException} already applies to its three causes.
 * <p>
 * The assessment id IS returned, because support has to be able to answer "why was my payment
 * refused?" without guessing. That is a question for a human with database access, which is exactly
 * where the reasons should live.
 *
 * @param assessmentId the {@code rsk_} id of the recorded decision. Present whatever the outcome:
 *     an allowed payment has an assessment too, and being able to say "we looked, here is the
 *     record" is worth as much as the refusal.
 */
public interface RiskCheck {

    Decision evaluate(
        MerchantId merchantId,
        String paymentIntentId,
        long amountMinor,
        String currency,
        String customerId,
        String device
    );

    record Decision(boolean permitted, String assessmentId) {
    }
}
