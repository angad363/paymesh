package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;

import java.util.List;

/** @param nextCursor null when this is the last page */
public record RefundPage(List<Refund> refunds, RefundCursor nextCursor) {
}
