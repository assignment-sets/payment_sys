package com.example.payment_system.service;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.entity.*;
import com.example.payment_system.exception.*;
import com.example.payment_system.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferProcessor {

    private final TransferRepository transferRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final TransferRecoveryService transferRecoveryService;

    /**
     * Phase 1: Create the intent.
     * Relying on DB Unique Constraint to prevent race conditions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer createInitialRecord(CreateTransferRequest request) {
        try {
            Account fromAccount = accountService.getAccount(request.fromAccountId());
            Account toAccount = accountService.getAccount(request.toAccountId());

            // Fail fast: a frozen sender never even creates a transfer record.
            if (fromAccount.getStatus() == AccountStatus.FROZEN) {
                throw new AccountFrozenException(fromAccount.getId());
            }

            Transfer transfer = Transfer.builder()
                    .transactionId(UUID.randomUUID())
                    .fromAccount(fromAccount)
                    .toAccount(toAccount)
                    .amount(request.amount())
                    .status(TransferStatus.INITIATED)
                    .idempotencyKey(request.idempotencyKey())
                    .createdAt(OffsetDateTime.now())
                    .build();

            return transferRepository.save(transfer);
        } catch (DataIntegrityViolationException e) {
            // If the key exists, this is a duplicate. Let the Service handle it.
            throw new DuplicateTransferException(request.idempotencyKey());
        }
    }

    /**
     * Phase 2: Money movement with Retries.
     * Logic is wrapped in REQUIRED transaction.
     */
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50)
    )
    @Transactional
    public void executeMoneyMovement(UUID transactionId) {
        Transfer transfer = transferRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new TransferNotFoundException(transactionId));

        // If it's already posted, stop (idempotency safety)
        if (transfer.getStatus() == TransferStatus.POSTED) return;

        transfer.markPending();

        Account from = transfer.getFromAccount();
        Account to = transfer.getToAccount();

        // Deadlock prevention via ID ordering
        Account first = from.getId() < to.getId() ? from : to;
        Account second = first == from ? to : from;

        if (first == from) {
            accountService.debit(from, transfer.getAmount());
            accountService.credit(to, transfer.getAmount());
        } else {
            accountService.credit(to, transfer.getAmount());
            accountService.debit(from, transfer.getAmount());
        }

        ledgerService.recordDebit(transfer.getTransactionId(), from, transfer.getAmount());
        ledgerService.recordCredit(transfer.getTransactionId(), to, transfer.getAmount());

        transfer.markPosted();
    }

    /**
     * Recovery logic: Only runs after max retries are exhausted.
     * Delegates to TransferRecoveryService (a proxied bean) — calling
     * this.markFailed() here would bypass the proxy and silently no-op.
     *
     * By the time this runs, the retried transaction has already rolled back,
     * so the REQUIRES_NEW update cannot deadlock on row locks.
     */
    @Recover
    public void recover(OptimisticLockingFailureException ex, UUID transactionId) {
        log.warn("Transfer {} failed after exhausting retries on concurrent updates: {}", transactionId, ex.getMessage());
        transferRecoveryService.markFailed(transactionId, TransferFailureCategory.TRANSIENT, 3);
        throw ex;
    }
}