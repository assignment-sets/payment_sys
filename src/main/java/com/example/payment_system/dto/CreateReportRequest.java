package com.example.payment_system.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateReportRequest(
        @NotNull(message = "fromDate is required")
        LocalDate fromDate,

        @NotNull(message = "toDate is required")
        LocalDate toDate
) {}
