package com.example.payment_system.controller;

import com.example.payment_system.dto.UserRequest;
import com.example.payment_system.dto.UserResponse;
import com.example.payment_system.ratelimit.RateLimited;
import com.example.payment_system.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;

    @PreAuthorize("hasRole('ADMIN')")
    @RateLimited(capacity = 30, refillTokens = 30)
    @GetMapping
    public List<UserResponse> getAllUsers() {
        return userService.getAllUsers();
    }

    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.id")
    @RateLimited(capacity = 30, refillTokens = 30)
    @GetMapping("/{id}")
    public UserResponse getUser(@PathVariable @Positive Long id) {
        return userService.getUserById(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @RateLimited(capacity = 15, refillTokens = 10)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@Valid @RequestBody UserRequest request) {
        return userService.createUser(request);
    }
}
