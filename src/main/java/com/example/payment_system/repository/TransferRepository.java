package com.example.payment_system.repository;

import com.example.payment_system.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
            SET t.status = com.example.payment_system.entity.TransferStatus.FAILED
            WHERE t.transactionId = :transactionId
              AND t.status IN (com.example.payment_system.entity.TransferStatus.INITIATED,
                               com.example.payment_system.entity.TransferStatus.PENDING)
            """)
    int markFailedByTransactionId(@Param("transactionId") UUID transactionId);
}
