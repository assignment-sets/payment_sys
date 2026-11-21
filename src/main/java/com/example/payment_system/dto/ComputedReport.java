package com.example.payment_system.dto;

import java.util.List;

public record ComputedReport(
        long openingBalance,
        long closingBalance,
        long totalCredits,
        long totalDebits,
        long totalAdjustments,
        long netChange,
        int entryCount,
        List<ReportEntryItem> entries
) {}
