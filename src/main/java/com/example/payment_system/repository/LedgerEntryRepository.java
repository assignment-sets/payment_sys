package com.example.payment_system.repository;

import com.example.payment_system.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    List<LedgerEntry> findByAccountId(Long accountId);

    @Query("SELECT COALESCE(SUM(le.amount), 0) FROM LedgerEntry le " +
           "WHERE le.account.id = :accountId AND le.createdAt < :beforeExclusive")
    long sumAmountBefore(@Param("accountId") Long accountId,
                         @Param("beforeExclusive") OffsetDateTime beforeExclusive);

    List<LedgerEntry> findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
            Long accountId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive);
}
