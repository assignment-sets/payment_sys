package com.example.payment_system.entity;

import com.example.payment_system.dto.ReportEntryItem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of an account statement for a closed past date window
 * ({@code toDate < today UTC}). Once frozen, it is never recomputed.
 */
@Entity
@Table(name = "past_account_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PastAccountReport {

    @Id
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "from_date", nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false, updatable = false)
    private LocalDate toDate;

    @Column(name = "opening_balance", nullable = false, updatable = false)
    private Long openingBalance;

    @Column(name = "closing_balance", nullable = false, updatable = false)
    private Long closingBalance;

    @Column(name = "total_credits", nullable = false, updatable = false)
    private Long totalCredits;

    @Column(name = "total_debits", nullable = false, updatable = false)
    private Long totalDebits;

    @Column(name = "total_adjustments", nullable = false, updatable = false)
    private Long totalAdjustments;

    @Column(name = "net_change", nullable = false, updatable = false)
    private Long netChange;

    @Column(name = "entry_count", nullable = false, updatable = false)
    private Integer entryCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entries_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<ReportEntryItem> entries;

    @Column(name = "frozen_at", nullable = false, updatable = false)
    private OffsetDateTime frozenAt;
}
