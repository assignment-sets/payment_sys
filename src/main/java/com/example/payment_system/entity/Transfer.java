package com.example.payment_system.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /* =========================
       State machine rules
       ========================= */

    public void markPending() {
        ensureStatus(TransferStatus.INITIATED);
        this.status = TransferStatus.PENDING;
    }

    public void markPosted() {
        ensureStatus(TransferStatus.PENDING);
        this.status = TransferStatus.POSTED;
    }

    public void markFailed() {
        if (this.status == TransferStatus.POSTED) return; // Already successful, do nothing
        this.status = TransferStatus.FAILED;
    }

    public void markReversed() {
        ensureStatus(TransferStatus.POSTED);
        this.status = TransferStatus.REVERSED;
    }

    private void ensureStatus(TransferStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Invalid state transition from " + this.status + " to " + expected
            );
        }
    }
}
