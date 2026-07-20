package com.paymesh.merchant.api;

import com.paymesh.merchant.application.MerchantEmailAlreadyExistsException;
import com.paymesh.merchant.application.MerchantNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public final class MerchantExceptionHandler {

    @ExceptionHandler(MerchantEmailAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleMerchantEmailAlreadyExists(MerchantEmailAlreadyExistsException exception) {
        return ApiErrorResponse.of(
            "MERCHANT_EMAIL_ALREADY_EXISTS",
            exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleValidationFailure(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for(FieldError fieldError: exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                fieldError.getField(),
                fieldError.getDefaultMessage()
            );
        }
        return ApiErrorResponse.validation(fieldErrors);
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleMerchantNotFound(MerchantNotFoundException exception) {
        return ApiErrorResponse.of(
            "MERCHANT_NOT_FOUND",
            exception.getMessage()
        );
    }
}


