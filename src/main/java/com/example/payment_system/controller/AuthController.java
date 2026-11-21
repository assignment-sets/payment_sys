package com.example.payment_system.controller;

import com.example.payment_system.dto.AuthResponse;
import com.example.payment_system.dto.LoginRequest;
import com.example.payment_system.dto.RegisterRequest;
import com.example.payment_system.dto.UserResponse;
import com.example.payment_system.ratelimit.RateLimited;
import com.example.payment_system.security.AuthenticatedUser;
import com.example.payment_system.service.AuthService;
import com.example.payment_system.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @RateLimited(capacity = 10, refillTokens = 10)
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @RateLimited(capacity = 10, refillTokens = 10)
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new InsufficientAuthenticationException("Authentication is required");
        }
        return userService.getUserById(currentUser.id());
    }
}
