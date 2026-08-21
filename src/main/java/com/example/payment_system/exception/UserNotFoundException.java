package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

public class UserNotFoundException extends PaymentException {
    public UserNotFoundException(Long userId) {
        super("User not found: " + userId, HttpStatus.NOT_FOUND);
    }
}
