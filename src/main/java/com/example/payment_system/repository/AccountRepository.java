package com.example.payment_system.repository;

import com.example.payment_system.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Fetches the account with its user joined in a single query, avoiding
     * a lazy-load round trip when building response DTOs.
     */
    @Query("""
           select a from Account a
           join fetch a.user
           where a.user.id = :userId
           """)
    Optional<Account> findByUserId(@Param("userId") Long userId);

    @Query("""
           select a from Account a
           join fetch a.user
           where a.id = :id
           """)
    Optional<Account> findByIdWithUser(@Param("id") Long id);

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
