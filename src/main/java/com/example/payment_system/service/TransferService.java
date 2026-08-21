package com.example.payment_system.service;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.dto.TransferResponse;
import com.example.payment_system.entity.Transfer;
import com.example.payment_system.entity.TransferStatus;
import com.example.payment_system.exception.DuplicateTransferException;
import com.example.payment_system.exception.PaymentException;
import com.example.payment_system.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final TransferProcessor transferProcessor;
    private final TransferRecoveryService transferRecoveryService;

    public TransferResponse createTransfer(CreateTransferRequest request) {
        Transfer transfer;

        try {
            // 1. Create Initial Record
            transfer = transferProcessor.createInitialRecord(request);
        } catch (DuplicateTransferException e) {
            // 2. IDEMPOTENCY: If duplicate, fetch existing and return it
            transfer = transferRepository.findByFromAccountIdAndIdempotencyKey(
                            request.fromAccountId(), request.idempotencyKey())
                    .orElseThrow();

            // If already done or failed, just return the state
            if (transfer.getStatus() != TransferStatus.INITIATED) {
                log.info("Duplicate transfer request replayed, transactionId={}, status={}",
                        transfer.getTransactionId(), transfer.getStatus());
                return mapToResponse(transfer);
            }
        }

        // 3. Execute Money Movement (Proxied call - retries will work)
        try {
            transferProcessor.executeMoneyMovement(transfer.getTransactionId());
        } catch (PaymentException e) {
            // Business failure (insufficient balance, invalid state, ...):
            // deterministic, retrying cannot help. The money-movement tx has
            // already rolled back, so it is safe to mark FAILED in a new tx.
            // Rethrow so GlobalExceptionHandler maps it to a proper status code.
            log.warn("Transfer {} failed: {}", transfer.getTransactionId(), e.getMessage());
            transferRecoveryService.markFailed(transfer.getTransactionId());
            throw e;
        } catch (Exception e) {
            // Transient/infrastructure failure: leave the record INITIATED so the
            // zombie cleanup job can re-process or fail it later. Never swallow.
            log.error("Transfer {} failed with unexpected error", transfer.getTransactionId(), e);
            throw e;
        }

        // 4. Return final state after processing
        Transfer finalState = transferRepository.findByTransactionId(transfer.getTransactionId())
                .orElseThrow();

        log.info("Transfer {} completed with status={}", finalState.getTransactionId(), finalState.getStatus());
        return mapToResponse(finalState);
    }

    private TransferResponse mapToResponse(Transfer t) {
        return new TransferResponse(
                t.getTransactionId(),
                t.getStatus(),
                t.getAmount(),
                t.getCreatedAt()
        );
    }
}
