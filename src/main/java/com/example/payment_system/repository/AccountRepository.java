package com.example.payment_system.repository;

import com.example.payment_system.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByUserId(Long userId);

    /**
     * Use optimistic locking via @Version (default).
     * This method is just a semantic marker.
     */
    Optional<Account> findAccountById(Long id);

    /**
     * Optional: Pessimistic lock (NOT used initially)
     * We keep this here for future experiments.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);
}
