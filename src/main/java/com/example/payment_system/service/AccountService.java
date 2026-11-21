package com.example.payment_system.service;

import com.example.payment_system.cache.AccountCacheService;
import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.AccountStatus;
import com.example.payment_system.entity.User;
import com.example.payment_system.exception.AccountFrozenException;
import com.example.payment_system.exception.AccountNotFoundException;
import com.example.payment_system.exception.InsufficientBalanceException;
import com.example.payment_system.repository.AccountRepository;
import com.example.payment_system.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {
//  Never use @Transactional(propagation = Propagation.REQUIRES_NEW) else atomicity will be violated the methods are already being used inside a tx

    /**
     * Welcome bonus granted by the system on account creation. There is no
     * external funding mechanism, so this is the "source of money": a system
     * grant that must be ledger-backed like any other credit.
     */
    static final long SEED_GRANT_CENTS = 1000L; // $10

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final AccountCacheService accountCacheService;

    public AccountResponse createAccount(User user) {
        Account account = Account.builder()
                .user(user)
                .balance(SEED_GRANT_CENTS)
                .version(0L)
                .createdAt(OffsetDateTime.now())
                .build();

        Account savedAccount = accountRepository.save(account);

        // Ledger-back the seed grant in the same tx — otherwise every account
        // is born with +SEED_GRANT_CENTS drift and trips the reconciliation audit.
        // The random UUID acts as the system grant's pseudo transaction id.
        ledgerService.recordCredit(UUID.randomUUID(), savedAccount, SEED_GRANT_CENTS);

        return AccountResponse.fromEntity(savedAccount);
    }

    public AccountResponse getAccountByUserId(Long userId) {
        return accountCacheService.getByUserId(userId)
                .orElseGet(() -> {
                    Account account = accountRepository.findByUserId(userId)
                            .orElseThrow(() -> AccountNotFoundException.forUser(userId));
                    AccountResponse dto = AccountResponse.fromEntity(account);
                    accountCacheService.putIfNewer(account.getId(), userId, account.getVersion(), dto);
                    return dto;
                });
    }

    public AccountResponse getAccountResponse(Long accountId) {
        return accountCacheService.getByAccountId(accountId)
                .orElseGet(() -> {
                    Account account = accountRepository.findByIdWithUser(accountId)
                            .orElseThrow(() -> new AccountNotFoundException(accountId));
                    AccountResponse dto = AccountResponse.fromEntity(account);
                    accountCacheService.putIfNewer(accountId, account.getUser().getId(), account.getVersion(), dto);
                    return dto;
                });
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountResponse(Long accountId, AuthenticatedUser currentUser) {
        AccountResponse dto = getAccountResponse(accountId);
        if (currentUser != null && !currentUser.isAdmin()
                && !dto.userId().equals(currentUser.id())) {
            throw new AccessDeniedException("You do not have access to account " + accountId);
        }
        return dto;
    }

    @Transactional(readOnly = true)
    public void assertAccountOwner(Long accountId, Long userId) {
        Account account = accountRepository.findByIdWithUser(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("You do not own account " + accountId);
        }
    }

    @Transactional
    public AccountResponse updateStatus(Long accountId, AccountStatus status) {
        Account account = accountRepository.findByIdWithUser(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        account.setStatus(status);
        return AccountResponse.fromEntity(accountRepository.save(account));
    }

    public Account getAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public void debit(Account account, long amount) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountFrozenException(account.getId());
        }
        if (account.getBalance() < amount) {
            throw new InsufficientBalanceException(account.getId());
        }
        account.debit(amount);
        accountRepository.save(account);
    }

    public void credit(Account account, long amount) {
        account.credit(amount);
        accountRepository.save(account);
    }
}
