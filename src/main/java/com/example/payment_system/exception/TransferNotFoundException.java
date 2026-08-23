package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class TransferNotFoundException extends PaymentException {

    public TransferNotFoundException(UUID transactionId) {
        super("Transfer not found for transaction: " + transactionId, HttpStatus.NOT_FOUND);
    }
}
