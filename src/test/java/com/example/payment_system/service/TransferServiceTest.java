package com.example.payment_system.service;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.dto.IdempotentResult;
import com.example.payment_system.dto.TransferResponse;
import com.example.payment_system.entity.Transfer;
import com.example.payment_system.entity.TransferStatus;
import com.example.payment_system.exception.DuplicateTransferException;
import com.example.payment_system.exception.TransferNotFoundException;
import com.example.payment_system.repository.TransferRepository;
import com.example.payment_system.stream.TransferStreamProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferProcessor transferProcessor;

    @Mock
    private TransferStreamProducer transferStreamProducer;

    @InjectMocks
    private TransferService transferService;

    @Test
    void createTransfer_persistsInitiatedPublishesToStreamAndReturnsCreated() {
        UUID txId = UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 500L, "idem-1");
        Transfer initiated = Transfer.builder()
                .transactionId(txId)
                .amount(500L)
                .status(TransferStatus.INITIATED)
                .idempotencyKey("idem-1")
                .createdAt(OffsetDateTime.now())
                .build();

        when(transferProcessor.createInitialRecord(request)).thenReturn(initiated);

        IdempotentResult<TransferResponse> result = transferService.createTransfer(request);

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.data().transactionId()).isEqualTo(txId);
        assertThat(result.data().status()).isEqualTo(TransferStatus.INITIATED);
        verify(transferStreamProducer).publish(txId);
        verify(transferProcessor, never()).executeMoneyMovement(any());
    }

    @Test
    void createTransfer_onDuplicateReplay_returnsExistingWithoutRepublishing() {
        UUID txId = UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 500L, "idem-dup");
        Transfer existing = Transfer.builder()
                .transactionId(txId)
                .amount(500L)
                .status(TransferStatus.POSTED)
                .idempotencyKey("idem-dup")
                .createdAt(OffsetDateTime.now())
                .build();

        when(transferProcessor.createInitialRecord(request))
                .thenThrow(new DuplicateTransferException("idem-dup"));
        when(transferRepository.findByFromAccountIdAndIdempotencyKey(1L, "idem-dup"))
                .thenReturn(Optional.of(existing));

        IdempotentResult<TransferResponse> result = transferService.createTransfer(request);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.data().transactionId()).isEqualTo(txId);
        assertThat(result.data().status()).isEqualTo(TransferStatus.POSTED);
        verifyNoInteractions(transferStreamProducer);
    }

    @Test
    void getTransfer_whenFound_returnsResponse() {
        UUID txId = UUID.randomUUID();
        Transfer transfer = Transfer.builder()
                .transactionId(txId)
                .amount(250L)
                .status(TransferStatus.POSTED)
                .createdAt(OffsetDateTime.now())
                .build();

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));

        TransferResponse response = transferService.getTransfer(txId);

        assertThat(response.transactionId()).isEqualTo(txId);
        assertThat(response.status()).isEqualTo(TransferStatus.POSTED);
        assertThat(response.amount()).isEqualTo(250L);
    }

    @Test
    void getTransfer_whenMissing_throwsTransferNotFoundException() {
        UUID txId = UUID.randomUUID();
        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransfer(txId))
                .isInstanceOf(TransferNotFoundException.class);
    }
}
