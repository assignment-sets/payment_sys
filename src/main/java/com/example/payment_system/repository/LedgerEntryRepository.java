package com.example.payment_system.repository;

import com.example.payment_system.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    List<LedgerEntry> findByAccountId(Long accountId);
}
