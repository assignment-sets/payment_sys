package com.example.payment_system.controller;

import com.example.payment_system.dto.AccountReportResponse;
import com.example.payment_system.dto.CreateReportRequest;
import com.example.payment_system.ratelimit.RateLimited;
import com.example.payment_system.security.AuthenticatedUser;
import com.example.payment_system.security.SecurityProperties;
import com.example.payment_system.service.ReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class ReportController {

    private final ReportService reportService;
    private final SecurityProperties securityProperties;

    @RateLimited(capacity = 10, refillTokens = 5)
    @PostMapping("/accounts/{accountId}/reports")
    public ResponseEntity<AccountReportResponse> requestReport(
            @PathVariable @Positive Long accountId,
            @Valid @RequestBody CreateReportRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        AuthenticatedUser effectiveUser = resolveAuthenticatedUser(currentUser);
        ReportService.ReportCreationResult result = reportService.requestReport(accountId, request, effectiveUser);

        if (result.asyncQueued()) {
            URI location = URI.create("/api/reports/" + result.report().reportId());
            return ResponseEntity
                    .accepted()
                    .location(location)
                    .body(result.report());
        }

        return ResponseEntity.ok(result.report());
    }

    @GetMapping("/reports/{reportId}")
    public ResponseEntity<AccountReportResponse> getReport(
            @PathVariable UUID reportId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        AuthenticatedUser effectiveUser = resolveAuthenticatedUser(currentUser);
        return ResponseEntity.ok(reportService.getReport(reportId, effectiveUser));
    }

    @GetMapping("/reports/{reportId}/download")
    public ResponseEntity<byte[]> downloadReportCsv(
            @PathVariable UUID reportId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        AuthenticatedUser effectiveUser = resolveAuthenticatedUser(currentUser);
        ReportService.ReportDownload download = reportService.exportReportCsv(reportId, effectiveUser);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build());

        return ResponseEntity.ok()
                .headers(headers)
                .body(download.content());
    }

    private AuthenticatedUser resolveAuthenticatedUser(AuthenticatedUser currentUser) {
        if (securityProperties.isDisabled()) {
            return null;
        }
        if (currentUser == null) {
            throw new InsufficientAuthenticationException("Authentication is required");
        }
        return currentUser;
    }
}
