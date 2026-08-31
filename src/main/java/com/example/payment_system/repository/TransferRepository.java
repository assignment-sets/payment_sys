package com.example.payment_system.repository;

import com.example.payment_system.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByTransactionId(UUID transactionId);

    Optional<Transfer> findByFromAccountIdAndIdempotencyKey(
            Long fromAccountId,
            String idempotencyKey
    );

    /**
     * Terminal-failure transition done via a bulk update so it works on a fresh
     * transaction without loading/locking the entity, and so it can never
     * overwrite a POSTED transfer.
     */
    @Modifying
    @Query("""
            UPDATE Transfer t
            SET t.status = com.example.payment_system.entity.TransferStatus.FAILED,
                t.failureCategory = :category,
                t.attemptCount = :attempts
            WHERE t.transactionId = :transactionId
              AND t.status IN (com.example.payment_system.entity.TransferStatus.INITIATED,
                               com.example.payment_system.entity.TransferStatus.PENDING)
            """)
    int markFailedByTransactionId(@Param("transactionId") UUID transactionId,
                                  @Param("category") com.example.payment_system.entity.TransferFailureCategory category,
                                  @Param("attempts") int attempts);

    /**
     * Zombie cleanup: fails transfers stuck in INITIATED (e.g. server crash
     * mid-processing) older than the given cutoff. Tags them TIMEOUT so the
     * sweeper's conversions are distinguishable from business rejections and
     * retry-exhausted transient failures. The status guard ensures in-flight
     * or POSTED transfers are never touched.
     */
    @Modifying
    @Query("""
            UPDATE Transfer t
            SET t.status = com.example.payment_system.entity.TransferStatus.FAILED,
                t.failureCategory = com.example.payment_system.entity.TransferFailureCategory.TIMEOUT,
                t.attemptCount = 1
            WHERE t.status = com.example.payment_system.entity.TransferStatus.INITIATED
              AND t.createdAt < :cutoff
            """)
    int failStaleInitiated(@Param("cutoff") OffsetDateTime cutoff);
}
