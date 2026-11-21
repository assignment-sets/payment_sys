package com.example.payment_system.service;

import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.AccountStatus;
import com.example.payment_system.entity.LedgerEntry;
import com.example.payment_system.entity.LedgerEntryType;
import com.example.payment_system.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private LedgerService ledgerService;

    private Account sampleAccount() {
        return Account.builder()
                .id(1L)
                .balance(1000L)
                .version(0L)
                .status(AccountStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    @Test
    void recordDebit_withPositiveAmount_savesNegativeAmountDebitEntry() {
        UUID txId = UUID.randomUUID();
        Account account = sampleAccount();

        ledgerService.recordDebit(txId, account, 500L);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());

        LedgerEntry entry = captor.getValue();
        assertThat(entry.getTransactionId()).isEqualTo(txId);
        assertThat(entry.getAccount()).isSameAs(account);
        assertThat(entry.getAmount()).isEqualTo(-500L);
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void recordCredit_withPositiveAmount_savesPositiveAmountCreditEntry() {
        UUID txId = UUID.randomUUID();
        Account account = sampleAccount();

        ledgerService.recordCredit(txId, account, 750L);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());

        LedgerEntry entry = captor.getValue();
        assertThat(entry.getTransactionId()).isEqualTo(txId);
        assertThat(entry.getAccount()).isSameAs(account);
        assertThat(entry.getAmount()).isEqualTo(750L);
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void recordDebit_withZeroAmount_throwsIllegalArgumentException() {
        UUID txId = UUID.randomUUID();
        Account account = sampleAccount();

        assertThatThrownBy(() -> ledgerService.recordDebit(txId, account, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void recordDebit_withNegativeAmount_throwsIllegalArgumentException() {
        UUID txId = UUID.randomUUID();
        Account account = sampleAccount();

        assertThatThrownBy(() -> ledgerService.recordDebit(txId, account, -100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void recordCredit_withZeroAmount_throwsIllegalArgumentException() {
        UUID txId = UUID.randomUUID();
        Account account = sampleAccount();

        assertThatThrownBy(() -> ledgerService.recordCredit(txId, account, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void recordCredit_withNegativeAmount_throwsIllegalArgumentException() {
        UUID txId = UUID.randomUUID();
        Account account = sampleAccount();

        assertThatThrownBy(() -> ledgerService.recordCredit(txId, account, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        verifyNoInteractions(ledgerEntryRepository);
    }
}
