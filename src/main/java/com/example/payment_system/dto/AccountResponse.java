package com.example.payment_system.dto;

import com.example.payment_system.entity.Account;
import java.time.OffsetDateTime;

public record AccountResponse(
        Long id,
        Long userId,
        Long balance,
        OffsetDateTime createdAt
) {
    public static AccountResponse fromEntity(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getUser().getId(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }
}