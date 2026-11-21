package com.example.payment_system.service;

import com.example.payment_system.entity.TransferFailureCategory;
import com.example.payment_system.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferRecoveryServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @InjectMocks
    private TransferRecoveryService transferRecoveryService;

    @Test
    void markFailed_whenRowUpdated_delegatesWithCategoryAndAttempts() {
        UUID txId = UUID.randomUUID();
        when(transferRepository.markFailedByTransactionId(txId, TransferFailureCategory.TRANSIENT, 3))
                .thenReturn(1);

        assertThatCode(() -> transferRecoveryService.markFailed(txId, TransferFailureCategory.TRANSIENT, 3))
                .doesNotThrowAnyException();

        verify(transferRepository).markFailedByTransactionId(txId, TransferFailureCategory.TRANSIENT, 3);
    }

    @Test
    void markFailed_whenNoRowUpdated_completesGracefully() {
        UUID txId = UUID.randomUUID();
        when(transferRepository.markFailedByTransactionId(txId, TransferFailureCategory.TIMEOUT, 0))
                .thenReturn(0);

        assertThatCode(() -> transferRecoveryService.markFailed(txId, TransferFailureCategory.TIMEOUT, 0))
                .doesNotThrowAnyException();

        verify(transferRepository).markFailedByTransactionId(txId, TransferFailureCategory.TIMEOUT, 0);
    }

    @Test
    void markFailed_whenRepositoryThrows_propagatesException() {
        UUID txId = UUID.randomUUID();
        when(transferRepository.markFailedByTransactionId(txId, TransferFailureCategory.BUSINESS, 1))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> transferRecoveryService.markFailed(txId, TransferFailureCategory.BUSINESS, 1))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void markFailed_legacyOverload_defaultsToBusinessAndSingleAttempt() {
        UUID txId = UUID.randomUUID();
        when(transferRepository.markFailedByTransactionId(txId, TransferFailureCategory.BUSINESS, 1))
                .thenReturn(1);

        transferRecoveryService.markFailed(txId);

        verify(transferRepository).markFailedByTransactionId(txId, TransferFailureCategory.BUSINESS, 1);
    }
}
