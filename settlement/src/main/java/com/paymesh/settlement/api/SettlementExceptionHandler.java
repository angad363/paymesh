package com.paymesh.settlement.api;

import com.paymesh.settlement.application.SettlementBatchNotFoundException;
import com.paymesh.shared.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Settlement's HTTP translation, per-feature as the house rules require.
 *
 * <p>A malformed settlement id arrives as {@code IllegalArgumentException} from
 * {@code SettlementBatchId.from} and is answered 404 rather than 400: telling a caller their id is
 * the wrong SHAPE and a well-shaped unknown id is NOT FOUND leaks which ids could exist.
 */
@RestControllerAdvice(assignableTypes = {SettlementController.class})
public class SettlementExceptionHandler {

    @ExceptionHandler(SettlementBatchNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(SettlementBatchNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiErrorResponse("SETTLEMENT_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleMalformedId(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiErrorResponse("SETTLEMENT_NOT_FOUND", "Settlement was not found", null));
    }
}
