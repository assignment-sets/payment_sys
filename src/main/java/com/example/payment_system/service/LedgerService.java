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

    /**
     * Records a DEBIT entry. The amount must be positive; the sign is applied
     * here so callers cannot produce a sign/entry-type mismatch. The DB
     * chk_amount_sign constraint is the final backstop.
     */
    public void recordDebit(UUID transactionId, Account account, long amount) {
        requirePositive(amount);
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

    /**
     * Records a CREDIT entry. The amount must be positive and is stored as-is.
     */
    public void recordCredit(UUID transactionId, Account account, long amount) {
        requirePositive(amount);
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

    private void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Ledger amount must be positive, got: " + amount);
        }
    }
}
