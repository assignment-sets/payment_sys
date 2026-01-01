package com.example.payment_system.exception;

public class AccountNotFoundException extends PaymentException {

    public AccountNotFoundException(Long accountId) {
        super("Account not found: " + accountId);
    }
}
