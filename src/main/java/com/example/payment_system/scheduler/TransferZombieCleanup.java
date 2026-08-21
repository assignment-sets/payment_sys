package com.example.payment_system.scheduler;

import com.example.payment_system.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Zombie cleanup: a server crash mid-processing leaves transfers stuck in
 * INITIATED forever (the money-movement transaction rolled back, but the
 * INITIATED record was committed in its own REQUIRES_NEW tx).
 *
 * This job marks such records FAILED once they exceed the staleness threshold.
 * It deliberately does NOT re-process them — retries already ran during normal
 * execution; recovery/re-execution of failed transfers is a separate future
 * reconciliation concern.
 *
 * NOTE: safe to run idempotently, but when scaling to multiple app instances,
 * add ShedLock (or equivalent) so only one node sweeps at a time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "transfer.zombie-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class TransferZombieCleanup {

    private final TransferRepository transferRepository;

    @Value("${transfer.zombie-cleanup.stale-after-ms:300000}")
    private long staleAfterMs;

    @Scheduled(fixedDelayString = "${transfer.zombie-cleanup.interval-ms:600000}")
    @Transactional
    public void cleanZombies() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusNanos(staleAfterMs * 1_000_000);
        int cleaned = transferRepository.failStaleInitiated(cutoff);
        if (cleaned > 0) {
            log.warn("Zombie cleanup: marked {} stale INITIATED transfers as FAILED", cleaned);
        }
    }
}
