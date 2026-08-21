package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class InsufficientBalanceException extends PaymentException {

    public InsufficientBalanceException(Long accountId) {
        super("Insufficient balance for account: " + accountId, HttpStatus.PAYMENT_REQUIRED);
    }
}
