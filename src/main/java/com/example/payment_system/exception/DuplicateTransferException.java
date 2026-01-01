package com.example.payment_system.exception;

public class DuplicateTransferException extends PaymentException {

    public DuplicateTransferException(String idempotencyKey) {
        super("Duplicate transfer request with idempotency key: " + idempotencyKey);
    }
}
