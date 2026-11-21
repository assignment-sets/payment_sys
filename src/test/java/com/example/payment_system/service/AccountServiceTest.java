package com.example.payment_system.service;

import com.example.payment_system.cache.AccountCacheService;
import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.AccountStatus;
import com.example.payment_system.entity.Role;
import com.example.payment_system.entity.User;
import com.example.payment_system.exception.AccountFrozenException;
import com.example.payment_system.exception.AccountNotFoundException;
import com.example.payment_system.exception.InsufficientBalanceException;
import com.example.payment_system.repository.AccountRepository;
import com.example.payment_system.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private AccountCacheService accountCacheService;

    @InjectMocks
    private AccountService accountService;

    private User buildUser(Long id) {
        return User.builder()
                .id(id)
                .name("Alice")
                .email("alice@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private Account buildAccount(Long id, Long userId, long balance, AccountStatus status) {
        return Account.builder()
                .id(id)
                .user(buildUser(userId))
                .balance(balance)
                .version(1L)
                .status(status)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    @Test
    void createAccount_savesWithSeedGrantAndRecordsLedgerCredit() {
        User user = buildUser(10L);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account toSave = inv.getArgument(0);
            toSave.setId(100L);
            return toSave;
        });

        AccountResponse response = accountService.createAccount(user);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.userId()).isEqualTo(10L);
        assertThat(response.balance()).isEqualTo(AccountService.SEED_GRANT_CENTS);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getBalance()).isEqualTo(AccountService.SEED_GRANT_CENTS);
        assertThat(accountCaptor.getValue().getVersion()).isEqualTo(0L);
        assertThat(accountCaptor.getValue().getUser()).isSameAs(user);

        verify(ledgerService).recordCredit(any(UUID.class), eq(accountCaptor.getValue()), eq(AccountService.SEED_GRANT_CENTS));
    }

    @Test
    void getAccountByUserId_cacheHit_returnsCachedWithoutRepositoryLookup() {
        AccountResponse cached = new AccountResponse(5L, 10L, 1500L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(accountCacheService.getByUserId(10L)).thenReturn(Optional.of(cached));

        AccountResponse result = accountService.getAccountByUserId(10L);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(accountRepository);
        verify(accountCacheService, never()).putIfNewer(any(), any(), anyLong(), any());
    }

    @Test
    void getAccountByUserId_cacheMissAndFound_loadsFromRepoAndPopulatesCache() {
        Account account = buildAccount(5L, 10L, 2000L, AccountStatus.ACTIVE);
        when(accountCacheService.getByUserId(10L)).thenReturn(Optional.empty());
        when(accountRepository.findByUserId(10L)).thenReturn(Optional.of(account));

        AccountResponse result = accountService.getAccountByUserId(10L);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.userId()).isEqualTo(10L);
        assertThat(result.balance()).isEqualTo(2000L);
        verify(accountCacheService).putIfNewer(5L, 10L, 1L, result);
    }

    @Test
    void getAccountByUserId_cacheMissAndNotFound_throwsAccountNotFoundException() {
        when(accountCacheService.getByUserId(99L)).thenReturn(Optional.empty());
        when(accountRepository.findByUserId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountByUserId(99L))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountCacheService, never()).putIfNewer(any(), any(), anyLong(), any());
    }

    @Test
    void getAccountResponse_byId_cacheHit_returnsCachedWithoutRepositoryLookup() {
        AccountResponse cached = new AccountResponse(5L, 10L, 1500L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(accountCacheService.getByAccountId(5L)).thenReturn(Optional.of(cached));

        AccountResponse result = accountService.getAccountResponse(5L);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(accountRepository);
        verify(accountCacheService, never()).putIfNewer(any(), any(), anyLong(), any());
    }

    @Test
    void getAccountResponse_byId_cacheMissAndFound_loadsFromRepoAndPopulatesCache() {
        Account account = buildAccount(5L, 10L, 2500L, AccountStatus.ACTIVE);
        when(accountCacheService.getByAccountId(5L)).thenReturn(Optional.empty());
        when(accountRepository.findByIdWithUser(5L)).thenReturn(Optional.of(account));

        AccountResponse result = accountService.getAccountResponse(5L);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.userId()).isEqualTo(10L);
        assertThat(result.balance()).isEqualTo(2500L);
        verify(accountCacheService).putIfNewer(5L, 10L, 1L, result);
    }

    @Test
    void getAccountResponse_byId_cacheMissAndNotFound_throwsAccountNotFoundException() {
        when(accountCacheService.getByAccountId(404L)).thenReturn(Optional.empty());
        when(accountRepository.findByIdWithUser(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountResponse(404L))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountCacheService, never()).putIfNewer(any(), any(), anyLong(), any());
    }

    @Test
    void getAccountResponse_withAuthenticatedUser_whenNullUser_returnsAccount() {
        AccountResponse cached = new AccountResponse(5L, 10L, 1000L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(accountCacheService.getByAccountId(5L)).thenReturn(Optional.of(cached));

        AccountResponse result = accountService.getAccountResponse(5L, null);

        assertThat(result).isSameAs(cached);
    }

    @Test
    void getAccountResponse_withAuthenticatedUser_whenAdmin_returnsAnyAccount() {
        AccountResponse cached = new AccountResponse(5L, 10L, 1000L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(accountCacheService.getByAccountId(5L)).thenReturn(Optional.of(cached));
        AuthenticatedUser admin = new AuthenticatedUser(999L, "admin@example.com", "", Role.ADMIN);

        AccountResponse result = accountService.getAccountResponse(5L, admin);

        assertThat(result).isSameAs(cached);
    }

    @Test
    void getAccountResponse_withAuthenticatedUser_whenOwner_returnsAccount() {
        AccountResponse cached = new AccountResponse(5L, 10L, 1000L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(accountCacheService.getByAccountId(5L)).thenReturn(Optional.of(cached));
        AuthenticatedUser owner = new AuthenticatedUser(10L, "alice@example.com", "", Role.USER);

        AccountResponse result = accountService.getAccountResponse(5L, owner);

        assertThat(result).isSameAs(cached);
    }

    @Test
    void getAccountResponse_withAuthenticatedUser_whenNonOwnerUser_throwsAccessDenied() {
        AccountResponse cached = new AccountResponse(5L, 10L, 1000L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(accountCacheService.getByAccountId(5L)).thenReturn(Optional.of(cached));
        AuthenticatedUser otherUser = new AuthenticatedUser(20L, "bob@example.com", "", Role.USER);

        assertThatThrownBy(() -> accountService.getAccountResponse(5L, otherUser))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assertAccountOwner_whenOwner_succeeds() {
        Account account = buildAccount(5L, 10L, 1000L, AccountStatus.ACTIVE);
        when(accountRepository.findByIdWithUser(5L)).thenReturn(Optional.of(account));

        accountService.assertAccountOwner(5L, 10L);
    }

    @Test
    void assertAccountOwner_whenNonOwner_throwsAccessDenied() {
        Account account = buildAccount(5L, 10L, 1000L, AccountStatus.ACTIVE);
        when(accountRepository.findByIdWithUser(5L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.assertAccountOwner(5L, 20L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assertAccountOwner_whenNotFound_throwsAccountNotFound() {
        when(accountRepository.findByIdWithUser(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.assertAccountOwner(404L, 10L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void updateStatus_toFrozenAndActive_updatesAndSaves() {
        Account account = buildAccount(5L, 10L, 1000L, AccountStatus.ACTIVE);
        when(accountRepository.findByIdWithUser(5L)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        AccountResponse frozen = accountService.updateStatus(5L, AccountStatus.FROZEN);
        assertThat(frozen.status()).isEqualTo(AccountStatus.FROZEN);

        AccountResponse active = accountService.updateStatus(5L, AccountStatus.ACTIVE);
        assertThat(active.status()).isEqualTo(AccountStatus.ACTIVE);

        verify(accountRepository, times(2)).save(account);
    }

    @Test
    void updateStatus_whenNotFound_throwsAccountNotFound() {
        when(accountRepository.findByIdWithUser(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.updateStatus(404L, AccountStatus.FROZEN))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void getAccount_whenFound_returnsEntity() {
        Account account = buildAccount(5L, 10L, 1000L, AccountStatus.ACTIVE);
        when(accountRepository.findById(5L)).thenReturn(Optional.of(account));

        assertThat(accountService.getAccount(5L)).isSameAs(account);
    }

    @Test
    void getAccount_whenNotFound_throwsAccountNotFound() {
        when(accountRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(404L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void debit_whenActiveAndSufficientBalance_debitsAndSaves() {
        Account account = buildAccount(5L, 10L, 1000L, AccountStatus.ACTIVE);

        accountService.debit(account, 400L);

        assertThat(account.getBalance()).isEqualTo(600L);
        verify(accountRepository).save(account);
    }

    @Test
    void debit_whenFrozen_throwsAccountFrozenWithoutSaving() {
        Account account = buildAccount(5L, 10L, 1000L, AccountStatus.FROZEN);

        assertThatThrownBy(() -> accountService.debit(account, 400L))
                .isInstanceOf(AccountFrozenException.class);

        assertThat(account.getBalance()).isEqualTo(1000L);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void debit_whenInsufficientBalance_throwsInsufficientBalanceWithoutSaving() {
        Account account = buildAccount(5L, 10L, 300L, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> accountService.debit(account, 400L))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(account.getBalance()).isEqualTo(300L);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void credit_addsAmountAndSaves() {
        Account account = buildAccount(5L, 10L, 1000L, AccountStatus.ACTIVE);

        accountService.credit(account, 500L);

        assertThat(account.getBalance()).isEqualTo(1500L);
        verify(accountRepository).save(account);
    }
}
