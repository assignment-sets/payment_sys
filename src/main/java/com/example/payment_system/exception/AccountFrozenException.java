package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class AccountFrozenException extends PaymentException {

    public AccountFrozenException(Long accountId) {
        super("Account is frozen and cannot send transfers: " + accountId, HttpStatus.FORBIDDEN);
    }
}
