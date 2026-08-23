package com.example.payment_system.service;

import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.User;
import com.example.payment_system.exception.AccountNotFoundException;
import com.example.payment_system.exception.InsufficientBalanceException;
import com.example.payment_system.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AccountService {
//  Never use @Transactional(propagation = Propagation.REQUIRES_NEW) else atomicity will be violated the methods are already being used inside a tx

    private final AccountRepository accountRepository;

    public AccountResponse createAccount(User user) {
        Account account = Account.builder()
                .user(user)
                .balance(1000L) // $10
                .version(0L)
                .createdAt(OffsetDateTime.now())
                .build();

        return AccountResponse.fromEntity(accountRepository.save(account));
    }

    public Account getAccountByUserId(Long userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> AccountNotFoundException.forUser(userId));
    }

    public Account getAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public void debit(Account account, long amount) {
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
