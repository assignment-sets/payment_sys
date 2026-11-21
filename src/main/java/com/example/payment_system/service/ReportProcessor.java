package com.example.payment_system.service;

import com.example.payment_system.dto.ComputedReport;
import com.example.payment_system.entity.ReportJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Worker-side orchestrator for queued current-period report jobs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportProcessor {

    private final ReportPersistenceService reportPersistenceService;
    private final ReportCalculationService reportCalculationService;

    public void process(UUID reportId) {
        Optional<ReportJob> maybeJob = reportPersistenceService.markProcessing(reportId);
        if (maybeJob.isEmpty()) {
            log.debug("Report {} already completed, skipping duplicate delivery", reportId);
            return;
        }

        ReportJob job = maybeJob.get();
        ComputedReport computed = reportCalculationService.calculate(
                job.getAccountId(), job.getFromDate(), job.getToDate());
        reportPersistenceService.completeJob(reportId, computed);
        log.info("Completed async report {} for account {} ({}..{}, entries={})",
                reportId, job.getAccountId(), job.getFromDate(), job.getToDate(), computed.entryCount());
    }

    public void markFailed(UUID reportId, String reason) {
        reportPersistenceService.markFailed(reportId, reason);
    }
}
