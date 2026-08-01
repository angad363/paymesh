package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;

import java.util.List;

/**
 * One page of payment intents plus what a caller needs to ask for the next one.
 *
 * @param paymentIntents the page, newest first
 * @param nextCursor     the encoded position to resume from, or null when there is nothing after
 * @param hasMore        whether another page exists; always {@code nextCursor != null}, stated
 *                       separately because the response contract names both
 * @param limit          the limit actually applied, which is not always the one that was asked for
 */
public record PaymentIntentPage(
    List<PaymentIntent> paymentIntents,
    String nextCursor,
    boolean hasMore,
    int limit
) {
}
