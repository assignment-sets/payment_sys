package com.example.payment_system.service;

import com.example.payment_system.dto.ComputedReport;
import com.example.payment_system.dto.ReportEntryItem;
import com.example.payment_system.entity.LedgerEntry;
import com.example.payment_system.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Computes account statement totals and chronological ledger line items in a
 * read-only transaction (ready to point at a dedicated read replica later).
 */
@Service
@RequiredArgsConstructor
public class ReportCalculationService {

    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional(readOnly = true)
    public ComputedReport calculate(Long accountId, LocalDate fromDate, LocalDate toDate) {
        OffsetDateTime fromInclusive = fromDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toExclusive = toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        long openingBalance = ledgerEntryRepository.sumAmountBefore(accountId, fromInclusive);
        List<LedgerEntry> ledgerEntries = ledgerEntryRepository
                .findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
                        accountId, fromInclusive, toExclusive);

        long totalCredits = 0L;
        long totalDebits = 0L;
        long totalAdjustments = 0L;
        long netChange = 0L;

        for (LedgerEntry entry : ledgerEntries) {
            long amount = entry.getAmount();
            netChange += amount;
            switch (entry.getEntryType()) {
                case CREDIT -> totalCredits += amount;
                case DEBIT -> totalDebits += Math.abs(amount);
                case ADJUSTMENT -> totalAdjustments += amount;
            }
        }

        List<ReportEntryItem> items = ledgerEntries.stream()
                .map(ReportEntryItem::fromEntity)
                .toList();

        return new ComputedReport(
                openingBalance,
                openingBalance + netChange,
                totalCredits,
                totalDebits,
                totalAdjustments,
                netChange,
                items.size(),
                items
        );
    }
}
