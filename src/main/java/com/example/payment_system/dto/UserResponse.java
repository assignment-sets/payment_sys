package com.example.payment_system.dto;

import com.example.payment_system.entity.User;
import java.time.OffsetDateTime;

public record UserResponse(
        Long id,
        String name,
        String email,
        OffsetDateTime createdAt
) {
    public static UserResponse fromEntity(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt()
        );
    }
}