package com.example.payment_system.service;

import com.example.payment_system.cache.ReportCacheService;
import com.example.payment_system.dto.AccountReportResponse;
import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.dto.ComputedReport;
import com.example.payment_system.dto.CreateReportRequest;
import com.example.payment_system.dto.ReportEntryItem;
import com.example.payment_system.entity.*;
import com.example.payment_system.exception.InvalidReportRequestException;
import com.example.payment_system.exception.ReportNotReadyException;
import com.example.payment_system.repository.PastAccountReportRepository;
import com.example.payment_system.repository.ReportJobRepository;
import com.example.payment_system.security.AuthenticatedUser;
import com.example.payment_system.stream.ReportStreamProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private AccountService accountService;
    @Mock
    private PastAccountReportRepository pastAccountReportRepository;
    @Mock
    private ReportJobRepository reportJobRepository;
    @Mock
    private ReportCalculationService reportCalculationService;
    @Mock
    private ReportPersistenceService reportPersistenceService;
    @Mock
    private ReportStreamProducer reportStreamProducer;
    @Mock
    private ReportCacheService reportCacheService;

    @InjectMocks
    private ReportService reportService;

    private final AuthenticatedUser user = new AuthenticatedUser(1L, "user@test.local", "Test User", Role.USER);

    @Test
    void requestReport_pastRangeHit_returnsFrozenSnapshotWithoutQueueOrRecalculation() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate weekAgo = yesterday.minusDays(6);
        UUID reportId = UUID.randomUUID();

        PastAccountReport frozen = PastAccountReport.builder()
                .reportId(reportId)
                .accountId(10L)
                .fromDate(weekAgo)
                .toDate(yesterday)
                .openingBalance(1000L)
                .closingBalance(1200L)
                .totalCredits(200L)
                .totalDebits(0L)
                .totalAdjustments(0L)
                .netChange(200L)
                .entryCount(1)
                .entries(List.of())
                .frozenAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(accountService.getAccountResponse(10L, user)).thenReturn(dummyAccount(10L));
        when(reportCacheService.getPastReport(10L, weekAgo, yesterday)).thenReturn(Optional.empty());
        when(pastAccountReportRepository.findByAccountIdAndFromDateAndToDate(10L, weekAgo, yesterday))
                .thenReturn(Optional.of(frozen));

        ReportService.ReportCreationResult result = reportService.requestReport(
                10L, new CreateReportRequest(weekAgo, yesterday), user);

        assertThat(result.asyncQueued()).isFalse();
        assertThat(result.report().reportId()).isEqualTo(reportId);
        assertThat(result.report().source()).isEqualTo(ReportSource.FROZEN_SNAPSHOT);
        assertThat(result.report().status()).isEqualTo(ReportStatus.COMPLETED);
        verify(reportCacheService).putPastReport(result.report());
        verifyNoInteractions(reportCalculationService, reportPersistenceService, reportStreamProducer);
    }

    @Test
    void requestReport_pastRangeMiss_computesAndFreezesOnce() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        ComputedReport computed = new ComputedReport(1000L, 1000L, 0L, 0L, 0L, 0L, 0, List.of());
        UUID frozenId = UUID.randomUUID();
        PastAccountReport frozen = PastAccountReport.builder()
                .reportId(frozenId)
                .accountId(10L)
                .fromDate(yesterday)
                .toDate(yesterday)
                .openingBalance(1000L)
                .closingBalance(1000L)
                .totalCredits(0L)
                .totalDebits(0L)
                .totalAdjustments(0L)
                .netChange(0L)
                .entryCount(0)
                .entries(List.of())
                .frozenAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(accountService.getAccountResponse(10L, user)).thenReturn(dummyAccount(10L));
        when(pastAccountReportRepository.findByAccountIdAndFromDateAndToDate(10L, yesterday, yesterday))
                .thenReturn(Optional.empty());
        when(reportCalculationService.calculate(10L, yesterday, yesterday)).thenReturn(computed);
        when(reportPersistenceService.freezePastReport(10L, yesterday, yesterday, computed)).thenReturn(frozen);

        ReportService.ReportCreationResult result = reportService.requestReport(
                10L, new CreateReportRequest(yesterday, yesterday), user);

        assertThat(result.asyncQueued()).isFalse();
        assertThat(result.report().reportId()).isEqualTo(frozenId);
        verifyNoInteractions(reportStreamProducer);
    }

    @Test
    void requestReport_currentRange_queuesAsyncJob() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        UUID jobId = UUID.randomUUID();
        ReportJob pending = ReportJob.builder()
                .reportId(jobId)
                .accountId(10L)
                .fromDate(today.minusDays(1))
                .toDate(today)
                .status(ReportStatus.PENDING)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(accountService.getAccountResponse(10L, user)).thenReturn(dummyAccount(10L));
        when(reportPersistenceService.createPendingJob(10L, today.minusDays(1), today)).thenReturn(pending);

        ReportService.ReportCreationResult result = reportService.requestReport(
                10L, new CreateReportRequest(today.minusDays(1), today), user);

        assertThat(result.asyncQueued()).isTrue();
        assertThat(result.report().reportId()).isEqualTo(jobId);
        assertThat(result.report().status()).isEqualTo(ReportStatus.PENDING);
        assertThat(result.report().source()).isEqualTo(ReportSource.ASYNC_QUEUE);
        verify(reportStreamProducer).publish(jobId);
        verifyNoInteractions(pastAccountReportRepository, reportCalculationService);
    }

    @Test
    void requestReport_invalidRange_throwsBadRequest() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(accountService.getAccountResponse(10L, user)).thenReturn(dummyAccount(10L));

        assertThatThrownBy(() -> reportService.requestReport(
                10L, new CreateReportRequest(today, today.minusDays(1)), user))
                .isInstanceOf(InvalidReportRequestException.class);

        assertThatThrownBy(() -> reportService.requestReport(
                10L, new CreateReportRequest(today.minusDays(400), today), user))
                .isInstanceOf(InvalidReportRequestException.class);
    }

    @Test
    void exportReportCsv_whenPending_throwsConflict_whenCompleted_returnsCsv() {
        UUID pendingId = UUID.randomUUID();
        ReportJob pending = ReportJob.builder()
                .reportId(pendingId)
                .accountId(10L)
                .fromDate(LocalDate.now(ZoneOffset.UTC))
                .toDate(LocalDate.now(ZoneOffset.UTC))
                .status(ReportStatus.PENDING)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(accountService.getAccountResponse(10L, user)).thenReturn(dummyAccount(10L));
        when(reportJobRepository.findByReportId(pendingId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> reportService.exportReportCsv(pendingId, user))
                .isInstanceOf(ReportNotReadyException.class);

        UUID doneId = UUID.randomUUID();
        PastAccountReport done = PastAccountReport.builder()
                .reportId(doneId)
                .accountId(10L)
                .fromDate(LocalDate.of(2026, 10, 1))
                .toDate(LocalDate.of(2026, 10, 2))
                .openingBalance(1000L)
                .closingBalance(1500L)
                .totalCredits(500L)
                .totalDebits(0L)
                .totalAdjustments(0L)
                .netChange(500L)
                .entryCount(1)
                .entries(List.of(new ReportEntryItem(
                        1L, UUID.randomUUID(), LedgerEntryType.CREDIT, 500L, OffsetDateTime.now(ZoneOffset.UTC))))
                .frozenAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(reportJobRepository.findByReportId(doneId)).thenReturn(Optional.empty());
        when(pastAccountReportRepository.findByReportId(doneId)).thenReturn(Optional.of(done));

        ReportService.ReportDownload download = reportService.exportReportCsv(doneId, user);
        String csv = new String(download.content(), StandardCharsets.UTF_8);
        assertThat(download.filename()).isEqualTo("account-10-report-2026-10-01-to-2026-10-02.csv");
        assertThat(csv).contains("accountId,fromDate,toDate,source,openingBalance,closingBalance");
        assertThat(csv).contains("10,2026-10-01,2026-10-02,FROZEN_SNAPSHOT,1000,1500,500,0,0,500,1");
        assertThat(csv).contains("CREDIT,500");
    }

    @Test
    void getReport_cacheHit_skipsDbAndStillChecksOwnership() {
        UUID reportId = UUID.randomUUID();
        AccountReportResponse cached = new AccountReportResponse(
                reportId, 10L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                ReportStatus.COMPLETED, ReportSource.ASYNC_QUEUE,
                1000L, 1200L, 200L, 0L, 0L, 200L, 0, List.of(), null,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));

        when(reportCacheService.getCompletedReport(reportId)).thenReturn(Optional.of(cached));
        when(accountService.getAccountResponse(10L, user)).thenReturn(dummyAccount(10L));

        AccountReportResponse result = reportService.getReport(reportId, user);

        assertThat(result).isEqualTo(cached);
        verify(accountService).getAccountResponse(10L, user);
        verifyNoInteractions(reportJobRepository, pastAccountReportRepository);
    }

    private AccountResponse dummyAccount(Long accountId) {
        return new AccountResponse(accountId, 1L, 1000L, AccountStatus.ACTIVE, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
