package com.example.payment_system.service;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.dto.TransferResponse;
import com.example.payment_system.entity.Transfer;
import com.example.payment_system.entity.TransferStatus;
import com.example.payment_system.exception.DuplicateTransferException;
import com.example.payment_system.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final TransferProcessor transferProcessor;

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
                return mapToResponse(transfer);
            }
        }

        // 3. Execute Money Movement (Proxied call - retries will work)
        try {
            transferProcessor.executeMoneyMovement(transfer.getTransactionId());
        } catch (Exception e) {
            // Errors here are handled by @Recover or logged
        }

        // 4. Return final state after processing
        Transfer finalState = transferRepository.findByTransactionId(transfer.getTransactionId())
                .orElseThrow();

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