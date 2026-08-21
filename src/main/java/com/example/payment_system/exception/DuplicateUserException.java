package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class DuplicateUserException extends PaymentException {
    public DuplicateUserException(String email) {
        super("User with email already exists: " + email, HttpStatus.CONFLICT);
    }
}
