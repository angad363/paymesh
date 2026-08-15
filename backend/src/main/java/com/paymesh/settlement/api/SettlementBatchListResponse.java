package com.paymesh.settlement.api;

import java.util.List;

/** A wrapper object, not a bare array, so the response can grow a cursor without breaking callers. */
public record SettlementBatchListResponse(List<SettlementBatchResponse> settlements) {
}
