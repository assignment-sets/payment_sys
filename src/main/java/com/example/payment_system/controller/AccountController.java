package com.example.payment_system.controller;

import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.ratelimit.RateLimited;
import com.example.payment_system.security.AuthenticatedUser;
import com.example.payment_system.security.SecurityProperties;
import com.example.payment_system.service.AccountService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Validated
public class AccountController {

    private final AccountService accountService;
    private final SecurityProperties securityProperties;

    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
    @RateLimited(capacity = 30, refillTokens = 30)
    @GetMapping("/user/{userId}")
    public AccountResponse getAccountByUserId(@PathVariable @Positive Long userId) {
        return accountService.getAccountByUserId(userId);
    }

    @RateLimited(capacity = 30, refillTokens = 30)
    @GetMapping("/{id}")
    public AccountResponse getAccount(
            @PathVariable @Positive Long id,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        if (!securityProperties.isDisabled() && currentUser == null) {
            throw new InsufficientAuthenticationException("Authentication is required");
        }
        return accountService.getAccountResponse(id, securityProperties.isDisabled() ? null : currentUser);
    }
}
