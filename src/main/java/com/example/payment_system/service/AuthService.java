package com.example.payment_system.service;

import com.example.payment_system.dto.AuthResponse;
import com.example.payment_system.dto.LoginRequest;
import com.example.payment_system.dto.RegisterRequest;
import com.example.payment_system.dto.UserResponse;
import com.example.payment_system.entity.Role;
import com.example.payment_system.entity.User;
import com.example.payment_system.repository.UserRepository;
import com.example.payment_system.security.AuthenticatedUser;
import com.example.payment_system.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Public registration always assigns USER; ADMIN cannot be self-assigned.
        User savedUser = userService.createUserWithAccount(
                request.name(),
                request.email(),
                request.password(),
                Role.USER
        );

        AuthenticatedUser principal = AuthenticatedUser.fromUser(savedUser);
        String token = jwtService.generateToken(principal);
        return AuthResponse.bearer(token, jwtService.getExpirationMs(), UserResponse.fromEntity(savedUser));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.password())
        );

        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        User user = userRepository.findById(principal.id())
                .orElseThrow();

        String token = jwtService.generateToken(principal);
        return AuthResponse.bearer(token, jwtService.getExpirationMs(), UserResponse.fromEntity(user));
    }
}
