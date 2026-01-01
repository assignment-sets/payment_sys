package com.example.payment_system.dto;

import com.example.payment_system.entity.TransferStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferResponse(

        UUID transactionId,
        TransferStatus status,
        Long amount,
        OffsetDateTime createdAt
) {}
