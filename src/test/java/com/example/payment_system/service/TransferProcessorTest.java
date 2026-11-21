package com.example.payment_system.service;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.AccountStatus;
import com.example.payment_system.entity.Transfer;
import com.example.payment_system.entity.TransferFailureCategory;
import com.example.payment_system.entity.TransferStatus;
import com.example.payment_system.exception.AccountFrozenException;
import com.example.payment_system.exception.AccountNotFoundException;
import com.example.payment_system.exception.DuplicateTransferException;
import com.example.payment_system.exception.InsufficientBalanceException;
import com.example.payment_system.exception.InvalidTransferStateException;
import com.example.payment_system.exception.TransferNotFoundException;
import com.example.payment_system.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferProcessorTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private TransferRecoveryService transferRecoveryService;

    @InjectMocks
    private TransferProcessor transferProcessor;

    private Account account(Long id, long balance, AccountStatus status) {
        return Account.builder()
                .id(id)
                .balance(balance)
                .version(0L)
                .status(status)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private Transfer transfer(UUID txId, Account from, Account to, long amount, TransferStatus status) {
        return Transfer.builder()
                .transactionId(txId)
                .fromAccount(from)
                .toAccount(to)
                .amount(amount)
                .status(status)
                .idempotencyKey("idem-key-1")
                .createdAt(OffsetDateTime.now())
                .build();
    }

    // --- createInitialRecord ---

    @Test
    void createInitialRecord_whenAccountsActive_savesInitiatedTransfer() {
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 250L, "idem-1");

        when(accountService.getAccount(1L)).thenReturn(from);
        when(accountService.getAccount(2L)).thenReturn(to);
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));

        Transfer result = transferProcessor.createInitialRecord(request);

        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository).save(captor.capture());
        Transfer saved = captor.getValue();

        assertThat(result).isSameAs(saved);
        assertThat(saved.getTransactionId()).isNotNull();
        assertThat(saved.getFromAccount()).isSameAs(from);
        assertThat(saved.getToAccount()).isSameAs(to);
        assertThat(saved.getAmount()).isEqualTo(250L);
        assertThat(saved.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void createInitialRecord_whenFromAccountFrozen_throwsAccountFrozenWithoutSaving() {
        Account from = account(1L, 1000L, AccountStatus.FROZEN);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 250L, "idem-frozen");

        when(accountService.getAccount(1L)).thenReturn(from);
        when(accountService.getAccount(2L)).thenReturn(to);

        assertThatThrownBy(() -> transferProcessor.createInitialRecord(request))
                .isInstanceOf(AccountFrozenException.class);

        verify(transferRepository, never()).save(any());
    }

    @Test
    void createInitialRecord_whenToAccountFrozen_stillCreatesInitiatedRecord() {
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.FROZEN);
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 250L, "idem-to-frozen");

        when(accountService.getAccount(1L)).thenReturn(from);
        when(accountService.getAccount(2L)).thenReturn(to);
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));

        Transfer result = transferProcessor.createInitialRecord(request);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.INITIATED);
        verify(transferRepository).save(any(Transfer.class));
    }

    @Test
    void createInitialRecord_whenFromAccountNotFound_propagatesAccountNotFound() {
        CreateTransferRequest request = new CreateTransferRequest(99L, 2L, 250L, "idem-missing-from");
        when(accountService.getAccount(99L)).thenThrow(new AccountNotFoundException(99L));

        assertThatThrownBy(() -> transferProcessor.createInitialRecord(request))
                .isInstanceOf(AccountNotFoundException.class);

        verify(transferRepository, never()).save(any());
    }

    @Test
    void createInitialRecord_whenToAccountNotFound_propagatesAccountNotFound() {
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        CreateTransferRequest request = new CreateTransferRequest(1L, 99L, 250L, "idem-missing-to");
        when(accountService.getAccount(1L)).thenReturn(from);
        when(accountService.getAccount(99L)).thenThrow(new AccountNotFoundException(99L));

        assertThatThrownBy(() -> transferProcessor.createInitialRecord(request))
                .isInstanceOf(AccountNotFoundException.class);

        verify(transferRepository, never()).save(any());
    }

    @Test
    void createInitialRecord_onDataIntegrityViolation_throwsDuplicateTransferException() {
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 250L, "idem-dup");

        when(accountService.getAccount(1L)).thenReturn(from);
        when(accountService.getAccount(2L)).thenReturn(to);
        when(transferRepository.save(any(Transfer.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> transferProcessor.createInitialRecord(request))
                .isInstanceOf(DuplicateTransferException.class);
    }

    // --- executeMoneyMovement ---

    @Test
    void executeMoneyMovement_whenFromIdLessThanToId_debitsThenCreditsRecordsLedgerAndPosts() {
        UUID txId = UUID.randomUUID();
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        Transfer transfer = transfer(txId, from, to, 300L, TransferStatus.INITIATED);

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));

        transferProcessor.executeMoneyMovement(txId);

        InOrder order = inOrder(accountService, ledgerService);
        order.verify(accountService).debit(from, 300L);
        order.verify(accountService).credit(to, 300L);
        order.verify(ledgerService).recordDebit(txId, from, 300L);
        order.verify(ledgerService).recordCredit(txId, to, 300L);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.POSTED);
    }

    @Test
    void executeMoneyMovement_whenFromIdGreaterThanToId_creditsThenDebitsForDeadlockPrevention() {
        UUID txId = UUID.randomUUID();
        Account from = account(10L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        Transfer transfer = transfer(txId, from, to, 300L, TransferStatus.INITIATED);

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));

        transferProcessor.executeMoneyMovement(txId);

        InOrder order = inOrder(accountService, ledgerService);
        order.verify(accountService).credit(to, 300L);
        order.verify(accountService).debit(from, 300L);
        order.verify(ledgerService).recordDebit(txId, from, 300L);
        order.verify(ledgerService).recordCredit(txId, to, 300L);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.POSTED);
    }

    @Test
    void executeMoneyMovement_whenTransferNotFound_throwsTransferNotFound() {
        UUID txId = UUID.randomUUID();
        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferProcessor.executeMoneyMovement(txId))
                .isInstanceOf(TransferNotFoundException.class);

        verifyNoInteractions(accountService, ledgerService);
    }

    @Test
    void executeMoneyMovement_whenAlreadyPosted_returnsEarlyIdempotently() {
        UUID txId = UUID.randomUUID();
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        Transfer transfer = transfer(txId, from, to, 300L, TransferStatus.POSTED);

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));

        transferProcessor.executeMoneyMovement(txId);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.POSTED);
        verifyNoInteractions(accountService, ledgerService);
    }

    @Test
    void executeMoneyMovement_whenTransferFailedOrPending_throwsInvalidTransferState() {
        UUID txId = UUID.randomUUID();
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        Transfer transfer = transfer(txId, from, to, 300L, TransferStatus.FAILED);

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferProcessor.executeMoneyMovement(txId))
                .isInstanceOf(InvalidTransferStateException.class);

        verifyNoInteractions(accountService, ledgerService);
    }

    @Test
    void executeMoneyMovement_whenInsufficientBalance_propagatesWithoutRecordingLedger() {
        UUID txId = UUID.randomUUID();
        Account from = account(1L, 100L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        Transfer transfer = transfer(txId, from, to, 300L, TransferStatus.INITIATED);

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));
        doThrow(new InsufficientBalanceException(1L)).when(accountService).debit(from, 300L);

        assertThatThrownBy(() -> transferProcessor.executeMoneyMovement(txId))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(accountService, never()).credit(any(), anyLong());
        verifyNoInteractions(ledgerService);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void executeMoneyMovement_whenFromAccountFrozen_propagatesWithoutRecordingLedger() {
        UUID txId = UUID.randomUUID();
        Account from = account(1L, 1000L, AccountStatus.FROZEN);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        Transfer transfer = transfer(txId, from, to, 300L, TransferStatus.INITIATED);

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));
        doThrow(new AccountFrozenException(1L)).when(accountService).debit(from, 300L);

        assertThatThrownBy(() -> transferProcessor.executeMoneyMovement(txId))
                .isInstanceOf(AccountFrozenException.class);

        verifyNoInteractions(ledgerService);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void executeMoneyMovement_whenOptimisticLockingFailure_propagatesForRetry() {
        UUID txId = UUID.randomUUID();
        Account from = account(1L, 1000L, AccountStatus.ACTIVE);
        Account to = account(2L, 500L, AccountStatus.ACTIVE);
        Transfer transfer = transfer(txId, from, to, 300L, TransferStatus.INITIATED);

        when(transferRepository.findByTransactionId(txId)).thenReturn(Optional.of(transfer));
        doThrow(new OptimisticLockingFailureException("stale version"))
                .when(accountService).debit(from, 300L);

        assertThatThrownBy(() -> transferProcessor.executeMoneyMovement(txId))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verifyNoInteractions(ledgerService);
    }

    // --- recover ---

    @Test
    void recover_marksTransferFailedAsTransientWithThreeAttemptsAndRethrows() {
        UUID txId = UUID.randomUUID();
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("retries exhausted");

        assertThatThrownBy(() -> transferProcessor.recover(ex, txId))
                .isSameAs(ex);

        verify(transferRecoveryService).markFailed(txId, TransferFailureCategory.TRANSIENT, 3);
    }
}
