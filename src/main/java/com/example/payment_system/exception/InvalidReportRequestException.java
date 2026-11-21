package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class InvalidReportRequestException extends PaymentException {

    public InvalidReportRequestException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
