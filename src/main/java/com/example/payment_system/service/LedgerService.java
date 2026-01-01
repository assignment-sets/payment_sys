package com.example.payment_system.service;

import com.example.payment_system.entity.*;
import com.example.payment_system.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public void recordDebit(UUID transactionId, Account account, long amount) {
        ledgerEntryRepository.save(
                LedgerEntry.builder()
                        .transactionId(transactionId)
                        .account(account)
                        .amount(-amount)
                        .entryType(LedgerEntryType.DEBIT)
                        .createdAt(OffsetDateTime.now())
                        .build()
        );
    }

    public void recordCredit(UUID transactionId, Account account, long amount) {
        ledgerEntryRepository.save(
                LedgerEntry.builder()
                        .transactionId(transactionId)
                        .account(account)
                        .amount(amount)
                        .entryType(LedgerEntryType.CREDIT)
                        .createdAt(OffsetDateTime.now())
                        .build()
        );
    }
}
