package com.example.payment_system.stream;

import com.example.payment_system.service.ReportProcessor;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Wraps {@link ReportProcessor#process(UUID)} with a dedicated Resilience4j
 * Bulkhead and CircuitBreaker so report bursts cannot starve live transfer
 * connections on the shared PostgreSQL pool.
 */
@Component
@RequiredArgsConstructor
public class ReportExecutionDelegate {

    private final ReportProcessor reportProcessor;

    @Bulkhead(name = "reportExecution")
    @CircuitBreaker(name = "reportExecution")
    public void execute(UUID reportId) {
        reportProcessor.process(reportId);
    }
}
