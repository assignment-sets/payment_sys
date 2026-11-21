package com.example.payment_system.service;

import com.example.payment_system.dto.ComputedReport;
import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.LedgerEntry;
import com.example.payment_system.entity.LedgerEntryType;
import com.example.payment_system.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportCalculationServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private ReportCalculationService calculationService;

    @Test
    void calculate_aggregatesOpeningClosingCreditsDebitsAndAdjustments() {
        Long accountId = 7L;
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        OffsetDateTime fromInclusive = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Account account = Account.builder().id(accountId).build();
        List<LedgerEntry> entries = List.of(
                LedgerEntry.builder()
                        .id(10L)
                        .transactionId(UUID.randomUUID())
                        .account(account)
                        .entryType(LedgerEntryType.CREDIT)
                        .amount(500L)
                        .createdAt(fromInclusive.plusHours(2))
                        .build(),
                LedgerEntry.builder()
                        .id(11L)
                        .transactionId(UUID.randomUUID())
                        .account(account)
                        .entryType(LedgerEntryType.DEBIT)
                        .amount(-200L)
                        .createdAt(fromInclusive.plusDays(1))
                        .build(),
                LedgerEntry.builder()
                        .id(12L)
                        .transactionId(UUID.randomUUID())
                        .account(account)
                        .entryType(LedgerEntryType.ADJUSTMENT)
                        .amount(50L)
                        .createdAt(fromInclusive.plusDays(2))
                        .build()
        );

        when(ledgerEntryRepository.sumAmountBefore(eq(accountId), eq(fromInclusive))).thenReturn(1000L);
        when(ledgerEntryRepository
                .findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
                        eq(accountId), eq(fromInclusive), eq(toExclusive)))
                .thenReturn(entries);

        ComputedReport report = calculationService.calculate(accountId, from, to);

        assertThat(report.openingBalance()).isEqualTo(1000L);
        assertThat(report.totalCredits()).isEqualTo(500L);
        assertThat(report.totalDebits()).isEqualTo(200L);
        assertThat(report.totalAdjustments()).isEqualTo(50L);
        assertThat(report.netChange()).isEqualTo(350L);
        assertThat(report.closingBalance()).isEqualTo(1350L);
        assertThat(report.entryCount()).isEqualTo(3);
        assertThat(report.entries()).hasSize(3);
    }
}
