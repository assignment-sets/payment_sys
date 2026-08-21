package com.example.payment_system.controller;

import com.example.payment_system.dto.CreateTransferRequest;
import com.example.payment_system.dto.TransferResponse;
import com.example.payment_system.ratelimit.RateLimited;
import com.example.payment_system.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
@CrossOrigin
public class TransferController {

    private final TransferService transferService;

    /**
     * Send money from one account to another.
     */
    @RateLimited(capacity = 15, refillTokens = 10)
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @Valid @RequestBody CreateTransferRequest request
    ) {
        TransferResponse response = transferService.createTransfer(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
