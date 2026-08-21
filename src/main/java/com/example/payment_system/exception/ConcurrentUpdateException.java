package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class ConcurrentUpdateException extends PaymentException {

    public ConcurrentUpdateException() {
        super("Concurrent modification detected. Please retry the operation.", HttpStatus.CONFLICT);
    }
}
