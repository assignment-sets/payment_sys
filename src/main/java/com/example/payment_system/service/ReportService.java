package com.example.payment_system.service;

import com.example.payment_system.cache.ReportCacheService;
import com.example.payment_system.dto.AccountReportResponse;
import com.example.payment_system.dto.ComputedReport;
import com.example.payment_system.dto.CreateReportRequest;
import com.example.payment_system.dto.ReportEntryItem;
import com.example.payment_system.entity.PastAccountReport;
import com.example.payment_system.entity.ReportJob;
import com.example.payment_system.entity.ReportStatus;
import com.example.payment_system.exception.InvalidReportRequestException;
import com.example.payment_system.exception.ReportNotFoundException;
import com.example.payment_system.exception.ReportNotReadyException;
import com.example.payment_system.repository.PastAccountReportRepository;
import com.example.payment_system.repository.ReportJobRepository;
import com.example.payment_system.security.AuthenticatedUser;
import com.example.payment_system.stream.ReportStreamProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates account statement requests:
 * <ul>
 *     <li>Past closed windows ({@code toDate < today UTC}) are served from
 *     the static {@code past_account_reports} table (computed once on first miss).</li>
 *     <li>Current/open windows ({@code toDate >= today UTC}) are queued on
 *     Redis Streams ({@code reports:pending}) to absorb load asynchronously.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    static final long MAX_REPORT_DAYS = 366L;

    private final AccountService accountService;
    private final PastAccountReportRepository pastAccountReportRepository;
    private final ReportJobRepository reportJobRepository;
    private final ReportCalculationService reportCalculationService;
    private final ReportPersistenceService reportPersistenceService;
    private final ReportStreamProducer reportStreamProducer;
    private final ReportCacheService reportCacheService;

    public record ReportCreationResult(AccountReportResponse report, boolean asyncQueued) {}

    public record ReportDownload(String filename, byte[] content) {}

    public ReportCreationResult requestReport(
            Long accountId, CreateReportRequest request, AuthenticatedUser currentUser) {
        accountService.getAccountResponse(accountId, currentUser);
        validateDateRange(request.fromDate(), request.toDate());

        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        if (request.toDate().isBefore(todayUtc)) {
            AccountReportResponse frozen = getOrFreezePastReport(accountId, request.fromDate(), request.toDate());
            return new ReportCreationResult(frozen, false);
        }

        ReportJob job = reportPersistenceService.createPendingJob(
                accountId, request.fromDate(), request.toDate());
        reportStreamProducer.publish(job.getReportId());
        log.info("Queued async report {} for account {} ({}..{})",
                job.getReportId(), accountId, request.fromDate(), request.toDate());
        return new ReportCreationResult(AccountReportResponse.fromJob(job), true);
    }

    @Transactional(readOnly = true)
    public AccountReportResponse getReport(UUID reportId, AuthenticatedUser currentUser) {
        return reportCacheService.getCompletedReport(reportId)
                .map(cached -> {
                    accountService.getAccountResponse(cached.accountId(), currentUser);
                    return cached;
                })
                .orElseGet(() -> {
                    AccountReportResponse report = findReportOrThrow(reportId);
                    accountService.getAccountResponse(report.accountId(), currentUser);
                    reportCacheService.putCompletedReport(report);
                    return report;
                });
    }

    @Transactional(readOnly = true)
    public ReportDownload exportReportCsv(UUID reportId, AuthenticatedUser currentUser) {
        AccountReportResponse report = getReport(reportId, currentUser);
        if (report.status() != ReportStatus.COMPLETED) {
            throw new ReportNotReadyException(reportId, report.status().name());
        }
        String filename = String.format("account-%d-report-%s-to-%s.csv",
                report.accountId(), report.fromDate(), report.toDate());
        return new ReportDownload(filename, buildCsv(report));
    }

    private AccountReportResponse getOrFreezePastReport(
            Long accountId, LocalDate fromDate, LocalDate toDate) {
        return reportCacheService.getPastReport(accountId, fromDate, toDate)
                .orElseGet(() -> {
                    AccountReportResponse report = pastAccountReportRepository
                            .findByAccountIdAndFromDateAndToDate(accountId, fromDate, toDate)
                            .map(AccountReportResponse::fromPastReport)
                            .orElseGet(() -> {
                                ComputedReport computed = reportCalculationService.calculate(accountId, fromDate, toDate);
                                PastAccountReport frozen = reportPersistenceService.freezePastReport(
                                        accountId, fromDate, toDate, computed);
                                log.info("Froze past report {} for account {} ({}..{}, entries={})",
                                        frozen.getReportId(), accountId, fromDate, toDate, frozen.getEntryCount());
                                return AccountReportResponse.fromPastReport(frozen);
                            });
                    reportCacheService.putPastReport(report);
                    return report;
                });
    }

    private AccountReportResponse findReportOrThrow(UUID reportId) {
        return reportJobRepository.findByReportId(reportId)
                .map(AccountReportResponse::fromJob)
                .orElseGet(() -> pastAccountReportRepository.findByReportId(reportId)
                        .map(AccountReportResponse::fromPastReport)
                        .orElseThrow(() -> new ReportNotFoundException(reportId)));
    }

    private void validateDateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            throw new InvalidReportRequestException("fromDate must not be after toDate");
        }
        long inclusiveDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (inclusiveDays > MAX_REPORT_DAYS) {
            throw new InvalidReportRequestException(
                    "Report range cannot exceed " + MAX_REPORT_DAYS + " days");
        }
    }

    private byte[] buildCsv(AccountReportResponse report) {
        StringBuilder sb = new StringBuilder();
        sb.append("accountId,fromDate,toDate,source,openingBalance,closingBalance,totalCredits,totalDebits,totalAdjustments,netChange,entryCount\n");
        sb.append(report.accountId()).append(',')
                .append(report.fromDate()).append(',')
                .append(report.toDate()).append(',')
                .append(report.source()).append(',')
                .append(report.openingBalance()).append(',')
                .append(report.closingBalance()).append(',')
                .append(report.totalCredits()).append(',')
                .append(report.totalDebits()).append(',')
                .append(report.totalAdjustments()).append(',')
                .append(report.netChange()).append(',')
                .append(report.entryCount()).append('\n');

        sb.append('\n');
        sb.append("ledgerEntryId,transactionId,entryType,amount,createdAt\n");
        List<ReportEntryItem> entries = report.entries() != null ? report.entries() : List.of();
        for (ReportEntryItem item : entries) {
            sb.append(item.ledgerEntryId()).append(',')
                    .append(item.transactionId()).append(',')
                    .append(item.entryType()).append(',')
                    .append(item.amount()).append(',')
                    .append(item.createdAt()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
