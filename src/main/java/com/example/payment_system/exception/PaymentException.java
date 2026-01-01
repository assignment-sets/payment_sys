package com.example.payment_system.exception;

public abstract class PaymentException extends RuntimeException {

    protected PaymentException(String message) {
        super(message);
    }
}
