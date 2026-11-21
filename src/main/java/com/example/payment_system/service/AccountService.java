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
import com.example.payment_system.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
        return accountRepository.findByUserId(userId)
                .map(AccountResponse::fromEntity)
                .orElseThrow(() -> AccountNotFoundException.forUser(userId));
    }

    public AccountResponse getAccountResponse(Long accountId) {
        return accountRepository.findByIdWithUser(accountId)
                .map(AccountResponse::fromEntity)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
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
