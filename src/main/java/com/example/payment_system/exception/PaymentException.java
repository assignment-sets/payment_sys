package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public abstract class PaymentException extends RuntimeException {

    private final HttpStatus status;

    protected PaymentException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
