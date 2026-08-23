package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends PaymentException {

    public AccountNotFoundException(Long accountId) {
        super("Account not found: " + accountId, HttpStatus.NOT_FOUND);
    }

    private AccountNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }

    public static AccountNotFoundException forUser(Long userId) {
        return new AccountNotFoundException("Account not found for user: " + userId);
    }
}
