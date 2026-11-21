package com.example.payment_system.dto;

import com.example.payment_system.entity.PastAccountReport;
import com.example.payment_system.entity.ReportJob;
import com.example.payment_system.entity.ReportSource;
import com.example.payment_system.entity.ReportStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AccountReportResponse(
        UUID reportId,
        Long accountId,
        LocalDate fromDate,
        LocalDate toDate,
        ReportStatus status,
        ReportSource source,
        Long openingBalance,
        Long closingBalance,
        Long totalCredits,
        Long totalDebits,
        Long totalAdjustments,
        Long netChange,
        Integer entryCount,
        List<ReportEntryItem> entries,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
    public static AccountReportResponse fromPastReport(PastAccountReport report) {
        return new AccountReportResponse(
                report.getReportId(),
                report.getAccountId(),
                report.getFromDate(),
                report.getToDate(),
                ReportStatus.COMPLETED,
                ReportSource.FROZEN_SNAPSHOT,
                report.getOpeningBalance(),
                report.getClosingBalance(),
                report.getTotalCredits(),
                report.getTotalDebits(),
                report.getTotalAdjustments(),
                report.getNetChange(),
                report.getEntryCount(),
                report.getEntries(),
                null,
                report.getFrozenAt(),
                report.getFrozenAt()
        );
    }

    public static AccountReportResponse fromJob(ReportJob job) {
        return new AccountReportResponse(
                job.getReportId(),
                job.getAccountId(),
                job.getFromDate(),
                job.getToDate(),
                job.getStatus(),
                ReportSource.ASYNC_QUEUE,
                job.getOpeningBalance(),
                job.getClosingBalance(),
                job.getTotalCredits(),
                job.getTotalDebits(),
                job.getTotalAdjustments(),
                job.getNetChange(),
                job.getEntryCount(),
                job.getEntries(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }
}
