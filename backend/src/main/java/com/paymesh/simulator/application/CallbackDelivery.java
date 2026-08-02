package com.paymesh.simulator.application;

/**
 * What came back from one delivery attempt.
 *
 * @param accepted      whether PayMesh answered 2xx. <b>The retry decision, and nothing else.</b>
 *                      A 404 is not accepted and is retried on purpose (ADR-012 section 7): the
 *                      likeliest cause is a callback overtaking the transaction that created the
 *                      intent
 * @param statusCode    the HTTP status, or null when the request never got an answer at all
 * @param outcome       APPLIED, DUPLICATE, IGNORED_STALE or IGNORED_TERMINAL, read out of the
 *                      response body. Null on any non-2xx. <b>The status code is the retry signal
 *                      and the body is the detail</b> -- a simulator recording only the code could
 *                      not tell whether the duplicate it carefully constructed actually
 *                      deduplicated, which is the assertion half its tests are about
 */
public record CallbackDelivery(boolean accepted, Integer statusCode, String outcome) {

    public static CallbackDelivery accepted(int statusCode, String outcome) {
        return new CallbackDelivery(true, statusCode, outcome);
    }

    public static CallbackDelivery refused(Integer statusCode) {
        return new CallbackDelivery(false, statusCode, null);
    }
}
