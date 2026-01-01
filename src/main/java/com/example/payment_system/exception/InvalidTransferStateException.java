package com.example.payment_system.exception;

import com.example.payment_system.entity.TransferStatus;

public class InvalidTransferStateException extends PaymentException {

    public InvalidTransferStateException(TransferStatus current, TransferStatus expected) {
        super("Invalid transfer state. Current: " + current + ", Expected: " + expected);
    }
}
