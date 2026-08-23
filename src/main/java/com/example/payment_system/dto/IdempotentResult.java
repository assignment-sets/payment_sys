package com.example.payment_system.dto;

public record IdempotentResult<T>(T data, boolean newlyCreated) {
    public static <T> IdempotentResult<T> created(T data) {
        return new IdempotentResult<>(data, true);
    }

    public static <T> IdempotentResult<T> existing(T data) {
        return new IdempotentResult<>(data, false);
    }
}