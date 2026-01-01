package com.example.payment_system.exception;

public class ConcurrentUpdateException extends PaymentException {

    public ConcurrentUpdateException() {
        super("Concurrent modification detected. Please retry the operation.");
    }
}
