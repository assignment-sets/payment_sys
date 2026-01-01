package com.example.payment_system.dto;

import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.User;
import java.time.OffsetDateTime;

public record DetailedUserResponse(
        Long id,
        String name,
        String email,
        Long accountId,
        Long balance,
        OffsetDateTime createdAt
) {
    public static DetailedUserResponse fromEntity(User user, Account account) {
        return new DetailedUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                account.getId(),
                account.getBalance(),
                user.getCreatedAt()
        );
    }
}