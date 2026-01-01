package com.example.payment_system.dto;

import jakarta.validation.constraints.*;

public record CreateTransferRequest(

        @Positive
        @NotNull
        Long fromAccountId,

        @Positive
        @NotNull
        Long toAccountId,

        @Min(1)
        @Max(100000000000L) // Example: Max 1 Billion Dollars (in cents)
        Long amount,

        @NotBlank
        String idempotencyKey
) {
}
