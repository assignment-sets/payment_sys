package com.example.payment_system.entity;

/**
 * Why a transfer ended up FAILED. Persisted in
 * transfers.failure_category for DLQ / audit queries;
 * no auto-retry is wired now — the category just makes a
 * future selective replay possible.
 */
public enum TransferFailureCategory {
    /** Deterministic business rejection (balance, frozen, invalid state, duplicate). */
    BUSINESS,
    /** Retry-exhausted infra contention (optimistic lock after 3 attempts). */
    TRANSIENT,
    /** Watchdog conversion: INITIATED row timed out without completing. */
    TIMEOUT
}
