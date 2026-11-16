package com.example.payment_system.stream;

import com.example.payment_system.service.TransferProcessor;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Separate Spring bean wrapping {@link TransferProcessor#executeMoneyMovement(UUID)}
 * with Resilience4j Bulkhead and CircuitBreaker so AOP proxy ordering stays clean
 * (Bulkhead -> CircuitBreaker -> Retryable -> Transactional).
 */
@Component
@RequiredArgsConstructor
public class TransferExecutionDelegate {

    private final TransferProcessor transferProcessor;

    @Bulkhead(name = "transferExecution")
    @CircuitBreaker(name = "transferExecution")
    public void execute(UUID transactionId) {
        transferProcessor.executeMoneyMovement(transactionId);
    }
}
