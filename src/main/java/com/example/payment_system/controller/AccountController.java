package com.example.payment_system.controller;

import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.ratelimit.RateLimited;
import com.example.payment_system.service.AccountService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Validated
@CrossOrigin(origins = "http://localhost:5173")
public class AccountController {

    private final AccountService accountService;

    @RateLimited(capacity = 30, refillTokens = 30)
    @GetMapping("/user/{userId}")
    public AccountResponse getAccountByUserId(@PathVariable @Positive Long userId) {
        return accountService.getAccountByUserId(userId);
    }

    @RateLimited(capacity = 30, refillTokens = 30)
    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable @Positive Long id) {
        return accountService.getAccountResponse(id);
    }
}
