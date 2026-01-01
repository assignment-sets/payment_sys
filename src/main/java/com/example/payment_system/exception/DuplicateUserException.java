package com.example.payment_system.exception;

public class DuplicateUserException extends PaymentException {
    public DuplicateUserException(String email) {
        super("User with email already exists: " + email);
    }
}
