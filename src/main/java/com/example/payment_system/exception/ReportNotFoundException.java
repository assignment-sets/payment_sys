package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ReportNotFoundException extends PaymentException {

    public ReportNotFoundException(UUID reportId) {
        super("Report not found: " + reportId, HttpStatus.NOT_FOUND);
    }
}
