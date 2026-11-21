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
 * Tracks an asynchronously generated account report for an active/current
 * date window ({@code toDate >= today UTC}).
 */
@Entity
@Table(name = "report_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportJob {

    @Id
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "from_date", nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false, updatable = false)
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "opening_balance")
    private Long openingBalance;

    @Column(name = "closing_balance")
    private Long closingBalance;

    @Column(name = "total_credits")
    private Long totalCredits;

    @Column(name = "total_debits")
    private Long totalDebits;

    @Column(name = "total_adjustments")
    private Long totalAdjustments;

    @Column(name = "net_change")
    private Long netChange;

    @Column(name = "entry_count")
    private Integer entryCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entries_json", columnDefinition = "jsonb")
    private List<ReportEntryItem> entries;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
