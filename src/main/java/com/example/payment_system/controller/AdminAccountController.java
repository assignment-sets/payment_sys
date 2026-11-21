package com.example.payment_system.controller;

import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.dto.UpdateAccountStatusRequest;
import com.example.payment_system.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
@Validated
public class AdminAccountController {

    private final AccountService accountService;

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public AccountResponse updateAccountStatus(
            @PathVariable @Positive Long id,
            @Valid @RequestBody UpdateAccountStatusRequest request
    ) {
        return accountService.updateStatus(id, request.status());
    }
}
