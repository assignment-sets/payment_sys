package com.example.payment_system.repository;

import com.example.payment_system.entity.ReportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {

    Optional<ReportJob> findByReportId(UUID reportId);
}
