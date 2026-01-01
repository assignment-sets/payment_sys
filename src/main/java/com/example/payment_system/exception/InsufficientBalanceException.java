package com.example.payment_system.exception;

public class InsufficientBalanceException extends PaymentException {

    public InsufficientBalanceException(Long accountId) {
        super("Insufficient balance for account: " + accountId);
    }
}
