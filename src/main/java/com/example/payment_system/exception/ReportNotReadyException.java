package com.example.payment_system.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ReportNotReadyException extends PaymentException {

    public ReportNotReadyException(UUID reportId, String status) {
        super("Report " + reportId + " is not ready for download (status=" + status + ")", HttpStatus.CONFLICT);
    }
}
