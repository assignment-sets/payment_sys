package com.example.payment_system.repository;

import com.example.payment_system.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByTransactionId(UUID transactionId);

    Optional<Transfer> findByFromAccountIdAndIdempotencyKey(
            Long fromAccountId,
            String idempotencyKey
    );
}
