package com.example.payment_system.dto;

import com.example.payment_system.entity.LedgerEntry;
import com.example.payment_system.entity.LedgerEntryType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReportEntryItem(
        Long ledgerEntryId,
        UUID transactionId,
        LedgerEntryType entryType,
        Long amount,
        OffsetDateTime createdAt
) {
    public static ReportEntryItem fromEntity(LedgerEntry entry) {
        return new ReportEntryItem(
                entry.getId(),
                entry.getTransactionId(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getCreatedAt()
        );
    }
}
