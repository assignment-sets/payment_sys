package com.example.payment_system.repository;

import com.example.payment_system.entity.PastAccountReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PastAccountReportRepository extends JpaRepository<PastAccountReport, UUID> {

    Optional<PastAccountReport> findByAccountIdAndFromDateAndToDate(
            Long accountId, LocalDate fromDate, LocalDate toDate);

    Optional<PastAccountReport> findByReportId(UUID reportId);
}
