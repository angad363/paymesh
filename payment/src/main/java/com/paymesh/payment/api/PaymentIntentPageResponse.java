package com.paymesh.payment.api;

import com.paymesh.payment.application.PaymentIntentPage;

import java.util.List;

/**
 * The collection envelope from conventions section 11: {@code {data, pagination}}, never a bare
 * array. The wrapper is what lets pagination metadata exist at all, and it keeps the response shape
 * from changing with the volume of data in it.
 */
public record PaymentIntentPageResponse(List<PaymentIntentResponse> data, Pagination pagination) {

    public static PaymentIntentPageResponse from(PaymentIntentPage page) {
        return new PaymentIntentPageResponse(
            page.paymentIntents().stream().map(PaymentIntentResponse::from).toList(),
            new Pagination(page.limit(), page.nextCursor(), page.hasMore())
        );
    }

    /**
     * @param nextCursor an OPAQUE token. Clients must not parse, modify or construct one -- its
     *                   encoding is the server's business and is free to change.
     */
    public record Pagination(int limit, String nextCursor, boolean hasMore) {
    }
}
