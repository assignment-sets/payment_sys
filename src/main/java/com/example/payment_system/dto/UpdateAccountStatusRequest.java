package com.example.payment_system.dto;

import com.example.payment_system.entity.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
        @NotNull
        AccountStatus status
) {}
