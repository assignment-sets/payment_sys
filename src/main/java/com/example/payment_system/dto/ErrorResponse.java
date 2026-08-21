package com.example.payment_system.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(OffsetDateTime.now(), status, null, message, null);
    }

    public static ErrorResponse validation(Map<String, String> fieldErrors) {
        return new ErrorResponse(OffsetDateTime.now(), 400, "Validation failed", null, fieldErrors);
    }
}
