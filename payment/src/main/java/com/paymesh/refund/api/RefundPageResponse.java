package com.paymesh.refund.api;

import com.paymesh.refund.application.RefundPage;

import java.util.List;

/** @param nextCursor null on the last page; opaque otherwise */
public record RefundPageResponse(List<RefundResponse> data, String nextCursor) {

    public static RefundPageResponse from(RefundPage page) {
        return new RefundPageResponse(
            page.refunds().stream().map(RefundResponse::from).toList(),
            page.nextCursor() == null ? null : page.nextCursor().encode()
        );
    }
}
