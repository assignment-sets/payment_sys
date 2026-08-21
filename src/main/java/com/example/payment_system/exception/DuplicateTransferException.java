package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class DuplicateTransferException extends PaymentException {

    public DuplicateTransferException(String idempotencyKey) {
        super("Duplicate transfer request with idempotency key: " + idempotencyKey, HttpStatus.CONFLICT);
    }
}
