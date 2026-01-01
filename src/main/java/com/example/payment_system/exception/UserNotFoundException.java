package com.example.payment_system.exception;

public class UserNotFoundException extends PaymentException {
    public UserNotFoundException(Long userId) {
        super("User not found: " + userId);
    }
}