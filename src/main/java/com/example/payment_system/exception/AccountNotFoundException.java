package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends PaymentException {

    public AccountNotFoundException(Long accountId) {
        super("Account not found: " + accountId, HttpStatus.NOT_FOUND);
    }
}
