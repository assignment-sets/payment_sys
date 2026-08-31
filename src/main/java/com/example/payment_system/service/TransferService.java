package com.example.payment_system.service;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.dto.IdempotentResult;
import com.example.payment_system.dto.TransferResponse;
import com.example.payment_system.entity.Transfer;
import com.example.payment_system.entity.TransferFailureCategory;
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

    public IdempotentResult<TransferResponse> createTransfer(CreateTransferRequest request) {
        Transfer transfer;

        try {
            // 1. Create Initial Record
            transfer = transferProcessor.createInitialRecord(request);
        } catch (DuplicateTransferException e) {
            // 2. IDEMPOTENCY: Fetch existing and return 200 OK state
            transfer = transferRepository.findByFromAccountIdAndIdempotencyKey(
                    request.fromAccountId(), request.idempotencyKey())
                    .orElseThrow();

            if (transfer.getStatus() != TransferStatus.INITIATED) {
                log.info("Duplicate transfer request replayed, transactionId={}, status={}",
                        transfer.getTransactionId(), transfer.getStatus());
                // Existing record -> newlyCreated = false
                return IdempotentResult.existing(mapToResponse(transfer));
            }
        }

        // 3. Execute Money Movement
        try {
            transferProcessor.executeMoneyMovement(transfer.getTransactionId());
        } catch (PaymentException e) {
            log.warn("Transfer {} failed: {}", transfer.getTransactionId(), e.getMessage());
            transferRecoveryService.markFailed(
                    transfer.getTransactionId(), TransferFailureCategory.BUSINESS, 1);
            throw e;
        } catch (Exception e) {
            log.error("Transfer {} failed with unexpected error", transfer.getTransactionId(), e);
            throw e;
        }

        // 4. Return final state after fresh processing
        Transfer finalState = transferRepository.findByTransactionId(transfer.getTransactionId())
                .orElseThrow();

        log.info("Transfer {} completed with status={}", finalState.getTransactionId(), finalState.getStatus());
        // Fresh execution -> newlyCreated = true
        return IdempotentResult.created(mapToResponse(finalState));
    }

    private TransferResponse mapToResponse(Transfer t) {
        return new TransferResponse(
                t.getTransactionId(),
                t.getStatus(),
                t.getAmount(),
                t.getCreatedAt());
    }
}