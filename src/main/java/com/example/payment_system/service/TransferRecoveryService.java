package com.example.payment_system.service;

import com.example.payment_system.entity.TransferFailureCategory;
import com.example.payment_system.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Owns terminal-failure transitions for transfers.
 *
 * Must be a separate bean from TransferProcessor so callers go through the
 * Spring proxy (self-invocation would silently skip @Transactional semantics).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferRecoveryService {

    private final TransferRepository transferRepository;

    /**
     * Marks a transfer FAILED in its own transaction with a categorized
     * failure. Callers choose BUSINESS (single deterministic rejection),
     * TRANSIENT (retry-exhausted), or TIMEOUT (watchdog conversion).
     *
     * Runs in REQUIRES_NEW because callers invoke this AFTER their money-movement
     * transaction has rolled back; a fresh tx guarantees the status change commits
     * even if surrounding context later fails.
     *
     * The guarded WHERE clause prevents a racing execution that is about to POST
     * from being clobbered by a stale failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID transactionId, TransferFailureCategory category, int attempts) {
        int updated = transferRepository.markFailedByTransactionId(transactionId, category, attempts);
        if (updated > 0) {
            log.warn("Transfer {} marked FAILED (category={}, attempts={})", transactionId, category, attempts);
        } else {
            log.info("Transfer {} not marked FAILED (already terminal or not found)", transactionId);
        }
    }

    /** Legacy overload — defaults to a single-attempt BUSINESS failure. */
    public void markFailed(UUID transactionId) {
        markFailed(transactionId, TransferFailureCategory.BUSINESS, 1);
    }
}
