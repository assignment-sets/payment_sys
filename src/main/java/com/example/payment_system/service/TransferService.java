package com.example.payment_system.service;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.dto.IdempotentResult;
import com.example.payment_system.dto.TransferResponse;
import com.example.payment_system.entity.Transfer;
import com.example.payment_system.exception.DuplicateTransferException;
import com.example.payment_system.exception.TransferNotFoundException;
import com.example.payment_system.repository.TransferRepository;
import com.example.payment_system.security.AuthenticatedUser;
import com.example.payment_system.stream.TransferStreamProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final TransferProcessor transferProcessor;
    private final TransferStreamProducer transferStreamProducer;

    public IdempotentResult<TransferResponse> createTransfer(CreateTransferRequest request) {
        Transfer transfer;

        try {
            // 1. Create Initial Record (INITIATED) in its own REQUIRES_NEW tx
            transfer = transferProcessor.createInitialRecord(request);
        } catch (DuplicateTransferException e) {
            // 2. IDEMPOTENCY: Fetch existing and return current state (200 OK)
            transfer = transferRepository.findByFromAccountIdAndIdempotencyKey(
                    request.fromAccountId(), request.idempotencyKey())
                    .orElseThrow();

            log.info("Duplicate transfer request replayed, transactionId={}, status={}",
                    transfer.getTransactionId(), transfer.getStatus());
            return IdempotentResult.existing(mapToResponse(transfer));
        }

        // 3. Enqueue for asynchronous worker execution
        transferStreamProducer.publish(transfer.getTransactionId());
        log.info("Transfer {} accepted and queued (status={})",
                transfer.getTransactionId(), transfer.getStatus());

        return IdempotentResult.created(mapToResponse(transfer));
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transactionId) {
        return transferRepository.findByTransactionId(transactionId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new TransferNotFoundException(transactionId));
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transactionId, AuthenticatedUser currentUser) {
        Transfer transfer = transferRepository.findByTransactionIdWithUsers(transactionId)
                .orElseThrow(() -> new TransferNotFoundException(transactionId));

        if (currentUser != null && !currentUser.isAdmin()) {
            Long senderUserId = transfer.getFromAccount().getUser().getId();
            Long receiverUserId = transfer.getToAccount().getUser().getId();
            if (!senderUserId.equals(currentUser.id()) && !receiverUserId.equals(currentUser.id())) {
                throw new AccessDeniedException("You do not have access to transfer " + transactionId);
            }
        }

        return mapToResponse(transfer);
    }

    private TransferResponse mapToResponse(Transfer t) {
        return new TransferResponse(
                t.getTransactionId(),
                t.getStatus(),
                t.getAmount(),
                t.getCreatedAt());
    }
}
