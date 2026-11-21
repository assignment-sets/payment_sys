package com.example.payment_system.service;

import com.example.payment_system.dto.ComputedReport;
import com.example.payment_system.entity.PastAccountReport;
import com.example.payment_system.entity.ReportJob;
import com.example.payment_system.entity.ReportStatus;
import com.example.payment_system.exception.ReportNotFoundException;
import com.example.payment_system.repository.PastAccountReportRepository;
import com.example.payment_system.repository.ReportJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles short, committed {@code REQUIRES_NEW} state transitions for
 * {@link PastAccountReport} and {@link ReportJob} so read-only report queries
 * and Redis Stream publishing never overlap open write transactions.
 */
@Service
@RequiredArgsConstructor
public class ReportPersistenceService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final PastAccountReportRepository pastAccountReportRepository;
    private final ReportJobRepository reportJobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PastAccountReport freezePastReport(
            Long accountId, LocalDate fromDate, LocalDate toDate, ComputedReport computed) {
        PastAccountReport snapshot = PastAccountReport.builder()
                .reportId(UUID.randomUUID())
                .accountId(accountId)
                .fromDate(fromDate)
                .toDate(toDate)
                .openingBalance(computed.openingBalance())
                .closingBalance(computed.closingBalance())
                .totalCredits(computed.totalCredits())
                .totalDebits(computed.totalDebits())
                .totalAdjustments(computed.totalAdjustments())
                .netChange(computed.netChange())
                .entryCount(computed.entryCount())
                .entries(computed.entries())
                .frozenAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        try {
            return pastAccountReportRepository.saveAndFlush(snapshot);
        } catch (DataIntegrityViolationException e) {
            // Concurrent first-time request froze this window first; return the winner.
            return pastAccountReportRepository
                    .findByAccountIdAndFromDateAndToDate(accountId, fromDate, toDate)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportJob createPendingJob(Long accountId, LocalDate fromDate, LocalDate toDate) {
        ReportJob job = ReportJob.builder()
                .reportId(UUID.randomUUID())
                .accountId(accountId)
                .fromDate(fromDate)
                .toDate(toDate)
                .status(ReportStatus.PENDING)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        return reportJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ReportJob> markProcessing(UUID reportId) {
        ReportJob job = reportJobRepository.findByReportId(reportId)
                .orElseThrow(() -> new ReportNotFoundException(reportId));
        if (job.getStatus() == ReportStatus.COMPLETED) {
            return Optional.empty();
        }
        job.setStatus(ReportStatus.PROCESSING);
        return Optional.of(reportJobRepository.save(job));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeJob(UUID reportId, ComputedReport computed) {
        ReportJob job = reportJobRepository.findByReportId(reportId)
                .orElseThrow(() -> new ReportNotFoundException(reportId));
        job.setStatus(ReportStatus.COMPLETED);
        job.setOpeningBalance(computed.openingBalance());
        job.setClosingBalance(computed.closingBalance());
        job.setTotalCredits(computed.totalCredits());
        job.setTotalDebits(computed.totalDebits());
        job.setTotalAdjustments(computed.totalAdjustments());
        job.setNetChange(computed.netChange());
        job.setEntryCount(computed.entryCount());
        job.setEntries(computed.entries());
        job.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        reportJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID reportId, String reason) {
        reportJobRepository.findByReportId(reportId).ifPresent(job -> {
            if (job.getStatus() == ReportStatus.COMPLETED) {
                return;
            }
            job.setStatus(ReportStatus.FAILED);
            job.setErrorMessage(truncate(reason));
            job.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            reportJobRepository.save(job);
        });
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
